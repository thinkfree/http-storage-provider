import { StorageError } from "./errors.mjs";

export const PROTOCOL_PREFIX = "/tfo-storage/v1";
export const STATE_DIRECTORY = ".tfo-http-storage-state";

const OPERATIONS = new Map([
  ["info", { method: "GET", bodyKind: "none" }],
  ["list", { method: "GET", bodyKind: "none" }],
  ["get", { method: "GET", bodyKind: "none" }],
  ["put", { method: "PUT", bodyKind: "document" }],
  ["lock", { method: "POST", bodyKind: "json" }],
  ["unlock", { method: "POST", bodyKind: "json" }],
  ["mkdir", { method: "POST", bodyKind: "json" }],
  ["rename", { method: "POST", bodyKind: "json" }],
  ["delete", { method: "DELETE", bodyKind: "none" }],
]);

export function parseStorageRoute(rawUrl, method) {
  if (
    typeof rawUrl !== "string" ||
    rawUrl.includes("?") ||
    rawUrl.includes("#")
  ) {
    throw new StorageError(
      400,
      "Query strings and fragments are not supported",
    );
  }
  if (!rawUrl.startsWith(`${PROTOCOL_PREFIX}/`)) {
    throw new StorageError(404, "Not found");
  }
  const rawSegments = rawUrl.slice(PROTOCOL_PREFIX.length + 1).split("/");
  if (rawSegments.some((segment) => !segment)) {
    throw new StorageError(400, "Empty path segments are not supported");
  }
  const operationName = rawSegments.pop();
  const operation = OPERATIONS.get(operationName);
  if (!operation) throw new StorageError(404, "Unknown storage operation");
  if (operation.method !== method)
    throw new StorageError(405, "Method not allowed");

  const segments = rawSegments.map(decodePathSegment);
  return Object.freeze({
    name: operationName,
    bodyKind: operation.bodyKind,
    rawPath: rawUrl,
    segments,
    documentPath: segments.join("/"),
  });
}

export function requirePathSegment(value) {
  if (
    typeof value !== "string" ||
    !value ||
    value === "." ||
    value === ".." ||
    value === STATE_DIRECTORY ||
    value.includes("/") ||
    value.includes("\\") ||
    [...value].some((character) => {
      const code = character.codePointAt(0);
      return code < 0x20 || code === 0x7f || code === 0;
    })
  ) {
    throw new StorageError(
      400,
      "The document path contains an unsupported segment",
    );
  }
  return value;
}

export function requireChildName(value) {
  if (typeof value !== "string" || value.length > 255) {
    throw new StorageError(400, "The name must contain 1 to 255 characters");
  }
  return requirePathSegment(value);
}

function decodePathSegment(rawSegment) {
  try {
    return requirePathSegment(decodeURIComponent(rawSegment));
  } catch (error) {
    if (error instanceof StorageError) throw error;
    throw new StorageError(
      400,
      "The document path is not valid UTF-8 percent-encoding",
    );
  }
}
