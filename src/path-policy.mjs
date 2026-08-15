import path from "node:path";
import {lstat, realpath} from "node:fs/promises";
import {HttpError} from "./http-error.mjs";

export const PROTOCOL_PREFIX = "/tfo-storage/v1";
export const STATE_DIRECTORY = ".tfo-http-storage-state";
export const OPERATIONS = new Set([
  "info",
  "list",
  "get",
  "put",
  "lock",
  "unlock",
  "mkdir",
  "rename",
  "delete",
]);

function validateDecodedSegment(segment) {
  if (!segment
      || segment === "."
      || segment === ".."
      || segment === STATE_DIRECTORY
      || segment.includes("/")
      || segment.includes("\\")
      || [...segment].some((character) => {
        const code = character.codePointAt(0);
        return code < 0x20 || code === 0x7f || code === 0;
      })) {
    throw new HttpError(400, "The document path contains an unsupported segment");
  }
  return segment;
}

function decodeSegment(rawSegment) {
  try {
    return validateDecodedSegment(decodeURIComponent(rawSegment));
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(400, "The document path is not valid UTF-8 percent-encoding");
  }
}

export function parseProtocolRoute(rawUrl, method) {
  if (typeof rawUrl !== "string" || rawUrl.includes("?") || rawUrl.includes("#")) {
    throw new HttpError(400, "Query strings and fragments are not supported");
  }
  if (!rawUrl.startsWith(`${PROTOCOL_PREFIX}/`)) {
    throw new HttpError(404, "Not found");
  }

  const rawRemainder = rawUrl.slice(PROTOCOL_PREFIX.length + 1);
  const rawSegments = rawRemainder.split("/");
  if (rawSegments.some((segment) => !segment)) {
    throw new HttpError(400, "Empty path segments are not supported");
  }
  const operation = rawSegments.pop();
  if (!OPERATIONS.has(operation)) {
    throw new HttpError(404, "Unknown storage operation");
  }

  const expectedMethod = {
    info: "GET",
    list: "GET",
    get: "GET",
    put: "PUT",
    lock: "POST",
    unlock: "POST",
    mkdir: "POST",
    rename: "POST",
    delete: "DELETE",
  }[operation];
  if (method !== expectedMethod) {
    throw new HttpError(405, "Method not allowed");
  }

  const segments = rawSegments.map(decodeSegment);
  return Object.freeze({
    operation,
    rawPath: rawUrl,
    segments,
    documentPath: segments.join("/"),
  });
}

export function validateChildName(value) {
  if (typeof value !== "string" || value.length > 255) {
    throw new HttpError(400, "The name must contain 1 to 255 characters");
  }
  return validateDecodedSegment(value);
}

export function containedPath(storageRoot, segments) {
  const candidate = path.resolve(storageRoot, ...segments);
  const relative = path.relative(storageRoot, candidate);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new HttpError(400, "The document path escapes the storage root");
  }
  return candidate;
}

export async function assertNoSymlinkPath(storageRoot, segments, allowMissingFinal = false) {
  const root = await realpath(storageRoot);
  let current = root;
  for (let index = 0; index < segments.length; index += 1) {
    current = path.join(current, segments[index]);
    try {
      const metadata = await lstat(current);
      if (metadata.isSymbolicLink()) {
        throw new HttpError(403, "Symbolic links are not available through this Provider");
      }
    } catch (error) {
      if (error instanceof HttpError) throw error;
      if (error?.code === "ENOENT" && allowMissingFinal && index === segments.length - 1) {
        return current;
      }
      throw error;
    }
  }
  return current;
}
