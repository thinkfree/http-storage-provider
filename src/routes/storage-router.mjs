import { createHash } from "node:crypto";
import express from "express";
import { pipeline } from "node:stream/promises";
import { StorageError } from "../domain/errors.mjs";
import {
  parseStorageRoute,
  PROTOCOL_PREFIX,
  requireChildName,
} from "../domain/storage-route.mjs";
import { sendText } from "../middleware/error-handler.mjs";
import { oneHeader } from "../security/request-jwt-verifier.mjs";

const EMPTY_SHA256 = createHash("sha256").update(Buffer.alloc(0)).digest("hex");
const JSON_BODY_LIMIT = 16 * 1024;

export function createStorageRouter({
  config,
  stateStore,
  storageService,
  requestVerifier,
}) {
  const router = express.Router();

  router.use(async (request, response, next) => {
    if (!request.originalUrl.startsWith(`${PROTOCOL_PREFIX}/`)) {
      next();
      return;
    }

    let requestBody;
    try {
      const route = parseStorageRoute(request.originalUrl, request.method);
      requestBody = await readRequestBody(
        request,
        route.bodyKind,
        config,
        stateStore,
      );
      await requestVerifier.verify(request, route, requestBody);
      // Capability information is returned only after the body, JWT, signed
      // request fields, and replay ID have been verified. Keep this branch
      // before storage access so PUT_NOT_SUPPORTED can never replace a file.
      if (config.unsupportedOperations?.has(route.name)) {
        sendOperationNotSupported(response, route.name);
        return;
      }
      await executeOperation(response, route, requestBody, storageService);
    } finally {
      if (requestBody?.stagingFile && !requestBody.committed) {
        await stateStore
          .removeStagingFile(requestBody.stagingFile)
          .catch(() => {});
      }
    }
  });
  return router;
}

function sendOperationNotSupported(response, operation) {
  // CloudOffice recognizes only HTTP 501 plus this exact single-field JSON
  // object. Extra fields and mismatched operation codes are normal failures.
  sendJson(response, { code: `${operation.toUpperCase()}_NOT_SUPPORTED` }, 501);
}

async function readRequestBody(request, bodyKind, config, stateStore) {
  if (bodyKind === "none") {
    rejectChunked(request);
    if (contentLength(request, false) !== 0) {
      throw new StorageError(
        400,
        "This operation does not accept a request body",
      );
    }
    return { length: 0, sha256: EMPTY_SHA256 };
  }
  if (bodyKind === "json") {
    requireContentType(request, "application/json");
    return readFixedBody(request, JSON_BODY_LIMIT);
  }
  requireContentType(request, "application/octet-stream");
  return stageDocument(request, stateStore, config.maxDocumentBytes);
}

async function readFixedBody(request, maximumBytes) {
  rejectChunked(request);
  const declaredLength = contentLength(request, true);
  if (declaredLength > maximumBytes)
    throw new StorageError(413, "The request body is too large");
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > declaredLength || length > maximumBytes) {
      throw new StorageError(
        400,
        "The request body does not match Content-Length",
      );
    }
    chunks.push(chunk);
  }
  if (length !== declaredLength) {
    throw new StorageError(
      400,
      "The request body does not match Content-Length",
    );
  }
  const bytes = Buffer.concat(chunks, length);
  return {
    bytes,
    length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

async function stageDocument(request, stateStore, maximumBytes) {
  rejectChunked(request);
  const declaredLength = contentLength(request, true);
  if (declaredLength > maximumBytes) {
    throw new StorageError(413, "The document exceeds the Provider size limit");
  }
  const { file, handle } = await stateStore.createStagingFile();
  const digest = createHash("sha256");
  let written = 0;
  try {
    for await (const chunk of request) {
      written += chunk.length;
      if (written > declaredLength || written > maximumBytes) {
        throw new StorageError(
          400,
          "The request body does not match Content-Length",
        );
      }
      digest.update(chunk);
      let offset = 0;
      while (offset < chunk.length) {
        const result = await handle.write(chunk, offset, chunk.length - offset);
        offset += result.bytesWritten;
      }
    }
    if (written !== declaredLength) {
      throw new StorageError(
        400,
        "The request body does not match Content-Length",
      );
    }
    await handle.sync();
    return {
      stagingFile: file,
      length: written,
      sha256: digest.digest("hex"),
      committed: false,
    };
  } catch (error) {
    await stateStore.removeStagingFile(file);
    throw error;
  } finally {
    await handle.close();
  }
}

async function executeOperation(response, route, body, storageService) {
  switch (route.name) {
    case "info":
      sendJson(response, await storageService.info(route.segments));
      return;
    case "list":
      sendJson(response, await storageService.list(route.segments));
      return;
    case "get": {
      const download = await storageService.download(route.segments);
      response.status(200).set({
        "Content-Type": "application/octet-stream",
        "Content-Length": download.contentLength,
        "Cache-Control": "no-store",
      });
      await pipeline(download.stream, response);
      return;
    }
    case "put": {
      const revision = await storageService.save(
        route.segments,
        body.stagingFile,
      );
      body.committed = true;
      sendText(response, 200, revision);
      return;
    }
    case "lock":
      await storageService.lock(
        route.segments,
        parseSingleField(body.bytes, "owner"),
      );
      response.status(204).set("Cache-Control", "no-store").end();
      return;
    case "unlock":
      await storageService.unlock(
        route.segments,
        parseSingleField(body.bytes, "owner"),
      );
      response.status(204).set("Cache-Control", "no-store").end();
      return;
    case "mkdir":
      await storageService.createDirectory(
        route.segments,
        requireChildName(parseSingleField(body.bytes, "name")),
      );
      response.status(204).set("Cache-Control", "no-store").end();
      return;
    case "rename":
      await storageService.rename(
        route.segments,
        requireChildName(parseSingleField(body.bytes, "name")),
      );
      response.status(204).set("Cache-Control", "no-store").end();
      return;
    case "delete":
      await storageService.delete(route.segments);
      response.status(204).set("Cache-Control", "no-store").end();
      return;
    default:
      throw new StorageError(404, "Unknown storage operation");
  }
}

function sendJson(response, value, statusCode = 200) {
  // INFO/LIST are small bounded metadata responses. Serialize once so the
  // Provider can publish the exact UTF-8 byte length instead of relying on
  // chunked transfer. Office rejects missing or ambiguous response framing.
  const body = Buffer.from(JSON.stringify(value));
  response.status(statusCode).set({
    "Content-Type": "application/json",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
  });
  response.end(body);
}

function parseSingleField(bytes, field) {
  let value;
  try {
    value = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new StorageError(400, "The request body must be valid JSON");
  }
  if (
    value === null ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).length !== 1 ||
    typeof value[field] !== "string" ||
    !value[field]
  ) {
    throw new StorageError(
      400,
      `The request body must contain only a non-empty ${field} string`,
    );
  }
  return value[field];
}

function requireContentType(request, expected) {
  if (oneHeader(request, "content-type") !== expected) {
    throw new StorageError(415, `This operation requires ${expected}`);
  }
}

function rejectChunked(request) {
  if (request.headers["transfer-encoding"] !== undefined) {
    throw new StorageError(400, "Chunked request bodies are not supported");
  }
}

function contentLength(request, required) {
  const value = oneHeader(request, "content-length");
  if (value === null) {
    if (required) throw new StorageError(411, "Content-Length is required");
    return 0;
  }
  if (!/^(0|[1-9][0-9]*)$/.test(value)) {
    throw new StorageError(
      400,
      "Content-Length must be a non-negative integer",
    );
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed))
    throw new StorageError(413, "The request body is too large");
  return parsed;
}
