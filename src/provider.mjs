import {createHash} from "node:crypto";
import {createReadStream} from "node:fs";
import {
  access,
  constants,
  lstat,
  mkdir,
  readdir,
  rename,
  rm,
  rmdir,
} from "node:fs/promises";
import path from "node:path";
import {pipeline} from "node:stream/promises";
import {verifyStorageRequest} from "./auth.mjs";
import {AuthenticationError, HttpError} from "./http-error.mjs";
import {
  assertNoSymlinkPath,
  containedPath,
  parseProtocolRoute,
  STATE_DIRECTORY,
  validateChildName,
} from "./path-policy.mjs";
import {ProviderStateStore} from "./state-store.mjs";

// This complete example deliberately maps protocol paths to one local
// directory. Replace the filesystem calls with production storage operations
// while preserving request verification, path containment, and lifecycle
// semantics.

const EMPTY_SHA256 = createHash("sha256").update(Buffer.alloc(0)).digest("hex");
const JSON_BODY_LIMIT = 16 * 1024;
const MAX_LIST_ENTRIES = 10_000;

function oneHeader(request, name) {
  const value = request.headers[name];
  return typeof value === "string" ? value : null;
}

function contentLengthHeader(request, required) {
  const value = oneHeader(request, "content-length");
  if (value === null) {
    if (required) throw new HttpError(411, "Content-Length is required");
    return 0;
  }
  if (!/^(0|[1-9][0-9]*)$/.test(value)) {
    throw new HttpError(400, "Content-Length must be a non-negative integer");
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) {
    throw new HttpError(413, "The request body is too large");
  }
  return parsed;
}

function rejectChunkedRequest(request) {
  if (request.headers["transfer-encoding"] !== undefined) {
    throw new HttpError(400, "Chunked request bodies are not supported");
  }
}

async function readFixedBody(request, maximumBytes) {
  rejectChunkedRequest(request);
  const declaredLength = contentLengthHeader(request, true);
  if (declaredLength > maximumBytes) {
    throw new HttpError(413, "The request body is too large");
  }
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > declaredLength || length > maximumBytes) {
      throw new HttpError(400, "The request body does not match Content-Length");
    }
    chunks.push(chunk);
  }
  if (length !== declaredLength) {
    throw new HttpError(400, "The request body does not match Content-Length");
  }
  const body = Buffer.concat(chunks, length);
  return {
    body,
    length,
    sha256: createHash("sha256").update(body).digest("hex"),
  };
}

async function stagePutBody(request, state, maximumBytes) {
  rejectChunkedRequest(request);
  const declaredLength = contentLengthHeader(request, true);
  if (declaredLength > maximumBytes) {
    throw new HttpError(413, "The document exceeds the Provider size limit");
  }

  const {file, handle} = await state.createStagingFile();
  const digest = createHash("sha256");
  let written = 0;
  try {
    for await (const chunk of request) {
      written += chunk.length;
      if (written > declaredLength || written > maximumBytes) {
        throw new HttpError(400, "The request body does not match Content-Length");
      }
      digest.update(chunk);
      let offset = 0;
      while (offset < chunk.length) {
        const {bytesWritten} = await handle.write(chunk, offset, chunk.length - offset);
        offset += bytesWritten;
      }
    }
    if (written !== declaredLength) {
      throw new HttpError(400, "The request body does not match Content-Length");
    }
    await handle.sync();
    return {file, length: written, sha256: digest.digest("hex")};
  } catch (error) {
    await state.removeStagingFile(file);
    throw error;
  } finally {
    await handle.close();
  }
}

function parseSingleStringField(body, field) {
  let value;
  try {
    value = JSON.parse(body.toString("utf8"));
  } catch {
    throw new HttpError(400, "The request body must be valid JSON");
  }
  if (value === null
      || typeof value !== "object"
      || Array.isArray(value)
      || Object.keys(value).length !== 1
      || typeof value[field] !== "string"
      || !value[field]) {
    throw new HttpError(400, `The request body must contain only a non-empty ${field} string`);
  }
  return value[field];
}

function sendBuffer(response, status, body, contentType) {
  response.writeHead(status, {
    "Content-Type": contentType,
    "Content-Length": body.length,
    "Cache-Control": "no-store",
  });
  response.end(body);
}

function sendText(response, status, text = "") {
  sendBuffer(response, status, Buffer.from(text, "utf8"), "text/plain; charset=utf-8");
}

function sendJson(response, status, value) {
  sendBuffer(
    response,
    status,
    Buffer.from(JSON.stringify(value), "utf8"),
    "application/json; charset=utf-8",
  );
}

async function permission(file, flag) {
  try {
    await access(file, flag);
    return true;
  } catch {
    return false;
  }
}

function revisionFor(metadata) {
  return `${Math.trunc(metadata.mtimeMs)}-${metadata.size}`;
}

async function entryFor({config, state, segments}) {
  const file = segments.length === 0
    ? config.storageRoot
    : await assertNoSymlinkPath(config.storageRoot, segments);
  let metadata;
  try {
    metadata = await lstat(file);
  } catch (error) {
    if (error?.code === "ENOENT") throw new HttpError(404, "Not found");
    throw error;
  }
  if (!metadata.isFile() && !metadata.isDirectory()) {
    throw new HttpError(403, "This storage item type is not supported");
  }

  const documentPath = segments.join("/");
  const lock = await state.currentLock(documentPath);
  return {
    path: documentPath,
    name: segments.at(-1) || config.rootName,
    type: metadata.isDirectory() ? "directory" : "file",
    size: metadata.isDirectory() ? 0 : metadata.size,
    readable: await permission(file, constants.R_OK),
    writable: await permission(file, constants.W_OK),
    locked: lock !== null,
    locker: lock?.owner ?? null,
    createdAt: metadata.birthtime.toISOString(),
    modifiedAt: metadata.mtime.toISOString(),
    revision: revisionFor(metadata),
  };
}

function operationBodyField(operation) {
  if (operation === "lock" || operation === "unlock") return "owner";
  if (operation === "mkdir" || operation === "rename") return "name";
  return null;
}

function requestBodyKind(operation) {
  if (operation === "put") return "put";
  if (operationBodyField(operation)) return "json";
  return "none";
}

async function authorize(request, route, config, state, bodyDetails) {
  const verified = verifyStorageRequest({
    token: oneHeader(request, "x-tfo-storage-request-jwt"),
    secret: config.requestJwtSecret,
    adapterHeader: oneHeader(request, "x-tfo-storage-adapter"),
    configuredAdapter: config.adapter,
    method: request.method,
    rawPath: route.rawPath,
    contentType: oneHeader(request, "content-type"),
    contentLength: bodyDetails.length,
    contentSha256: bodyDetails.sha256,
  });
  await state.consumeJti(verified.jti, verified.expiresAt);
  return verified;
}

async function ensureExistingDirectory(config, segments) {
  const directory = segments.length === 0
    ? config.storageRoot
    : await assertNoSymlinkPath(config.storageRoot, segments);
  const metadata = await lstat(directory).catch((error) => {
    if (error?.code === "ENOENT") throw new HttpError(404, "The parent directory does not exist");
    throw error;
  });
  if (!metadata.isDirectory()) throw new HttpError(409, "The parent path is not a directory");
  return directory;
}

async function executeOperation(request, response, route, config, state, bodyDetails) {
  switch (route.operation) {
    case "info": {
      sendJson(response, 200, await entryFor({config, state, segments: route.segments}));
      return;
    }
    case "list": {
      const directory = await ensureExistingDirectory(config, route.segments);
      const names = (await readdir(directory)).filter((name) => name !== STATE_DIRECTORY).sort();
      if (names.length > MAX_LIST_ENTRIES) {
        throw new HttpError(413, "The directory contains more than 10000 entries");
      }
      const entries = [];
      for (const name of names) {
        entries.push(await entryFor({config, state, segments: [...route.segments, name]}));
      }
      sendJson(response, 200, {entries});
      return;
    }
    case "get": {
      const file = await assertNoSymlinkPath(config.storageRoot, route.segments);
      const metadata = await lstat(file).catch((error) => {
        if (error?.code === "ENOENT") throw new HttpError(404, "Not found");
        throw error;
      });
      if (!metadata.isFile()) throw new HttpError(409, "The requested item is not a file");
      response.writeHead(200, {
        "Content-Type": "application/octet-stream",
        "Content-Length": metadata.size,
        "Cache-Control": "no-store",
      });
      await pipeline(createReadStream(file), response);
      return;
    }
    case "put": {
      if (route.segments.length === 0) throw new HttpError(400, "A document path is required");
      const target = containedPath(config.storageRoot, route.segments);
      await ensureExistingDirectory(config, route.segments.slice(0, -1));
      await assertNoSymlinkPath(config.storageRoot, route.segments, true);
      const current = await lstat(target).catch((error) => {
        if (error?.code === "ENOENT") return null;
        throw error;
      });
      if (current?.isDirectory()) throw new HttpError(409, "A directory already uses this path");
      await rename(bodyDetails.file, target);
      bodyDetails.committed = true;
      const metadata = await lstat(target);
      sendText(response, 200, revisionFor(metadata));
      return;
    }
    case "lock": {
      const owner = parseSingleStringField(bodyDetails.body, "owner");
      await entryFor({config, state, segments: route.segments});
      await state.acquireLock(route.documentPath, owner);
      sendText(response, 204);
      return;
    }
    case "unlock": {
      const owner = parseSingleStringField(bodyDetails.body, "owner");
      await state.releaseLock(route.documentPath, owner);
      sendText(response, 204);
      return;
    }
    case "mkdir": {
      const name = validateChildName(parseSingleStringField(bodyDetails.body, "name"));
      const parent = await ensureExistingDirectory(config, route.segments);
      const target = containedPath(config.storageRoot, [...route.segments, name]);
      await mkdir(target, {mode: 0o700}).catch((error) => {
        if (error?.code === "EEXIST") throw new HttpError(409, "An item already uses this name");
        throw error;
      });
      sendText(response, 204);
      return;
    }
    case "rename": {
      if (route.segments.length === 0) throw new HttpError(400, "The storage root cannot be renamed");
      const name = validateChildName(parseSingleStringField(bodyDetails.body, "name"));
      await state.requireUnlocked(route.documentPath);
      const source = await assertNoSymlinkPath(config.storageRoot, route.segments);
      const parentSegments = route.segments.slice(0, -1);
      await ensureExistingDirectory(config, parentSegments);
      const target = containedPath(config.storageRoot, [...parentSegments, name]);
      const targetExists = await lstat(target).then(() => true).catch((error) => {
        if (error?.code === "ENOENT") return false;
        throw error;
      });
      if (targetExists) throw new HttpError(409, "An item already uses this name");
      await rename(source, target);
      sendText(response, 204);
      return;
    }
    case "delete": {
      if (route.segments.length === 0) throw new HttpError(400, "The storage root cannot be deleted");
      await state.requireUnlocked(route.documentPath);
      const target = await assertNoSymlinkPath(config.storageRoot, route.segments);
      const metadata = await lstat(target);
      if (metadata.isDirectory()) await rmdir(target);
      else await rm(target, {force: false});
      sendText(response, 204);
      return;
    }
    default:
      throw new HttpError(404, "Unknown storage operation");
  }
}

export async function createProvider(config) {
  await mkdir(config.storageRoot, {recursive: true, mode: 0o700});
  const state = new ProviderStateStore(config.storageRoot, config.adapter);
  await state.initialize();

  return async function provider(request, response) {
    let stagedBody = null;
    try {
      if (request.method === "GET" && request.url === "/healthz") {
        sendText(response, 200, "ok\n");
        return;
      }

      const route = parseProtocolRoute(request.url, request.method);
      const kind = requestBodyKind(route.operation);
      let bodyDetails;
      if (kind === "put") {
        if (oneHeader(request, "content-type") !== "application/octet-stream") {
          throw new HttpError(415, "PUT requires application/octet-stream");
        }
        stagedBody = await stagePutBody(request, state, config.maxDocumentBytes);
        bodyDetails = stagedBody;
      } else if (kind === "json") {
        if (oneHeader(request, "content-type") !== "application/json") {
          throw new HttpError(415, "This operation requires application/json");
        }
        bodyDetails = await readFixedBody(request, JSON_BODY_LIMIT);
      } else {
        rejectChunkedRequest(request);
        if (contentLengthHeader(request, false) !== 0) {
          throw new HttpError(400, "This operation does not accept a request body");
        }
        bodyDetails = {length: 0, sha256: EMPTY_SHA256};
      }

      await authorize(request, route, config, state, bodyDetails);
      await executeOperation(request, response, route, config, state, bodyDetails);
    } catch (error) {
      if (response.headersSent) {
        response.destroy();
        return;
      }
      if (error instanceof AuthenticationError) {
        sendText(response, 401, "Unauthorized");
      } else if (error instanceof HttpError) {
        sendText(response, error.status, error.publicMessage);
      } else if (error?.code === "ENOENT") {
        sendText(response, 404, "Not found");
      } else if (error?.code === "ENOTEMPTY") {
        sendText(response, 409, "The directory is not empty");
      } else if (error?.code === "EACCES" || error?.code === "EPERM") {
        sendText(response, 403, "Storage access was denied");
      } else {
        console.error("Storage request failed");
        sendText(response, 500, "Storage request failed");
      }
    } finally {
      if (stagedBody && !stagedBody.committed) {
        await state.removeStagingFile(stagedBody.file).catch(() => {});
      }
    }
  };
}
