import path from "node:path";

const ADAPTER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

function parseInteger(name, value, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return parsed;
}

export function loadConfig(environment = process.env) {
  const adapter = environment.TFO_STORAGE_ADAPTER;
  const requestJwtSecret = environment.TFO_STORAGE_REQUEST_JWT_SECRET;
  if (!adapter || !ADAPTER_PATTERN.test(adapter)) {
    throw new Error(
      "TFO_STORAGE_ADAPTER must start with an ASCII letter or digit and contain only letters, digits, dot, underscore, or hyphen",
    );
  }
  if (!requestJwtSecret || Buffer.byteLength(requestJwtSecret, "utf8") < 32) {
    throw new Error("TFO_STORAGE_REQUEST_JWT_SECRET must contain at least 32 UTF-8 bytes");
  }

  return Object.freeze({
    host: environment.TFO_STORAGE_HOST || "127.0.0.1",
    port: parseInteger("TFO_STORAGE_PORT", environment.TFO_STORAGE_PORT || "8080", 1, 65535),
    storageRoot: path.resolve(environment.TFO_STORAGE_ROOT || "./storage"),
    rootName: environment.TFO_STORAGE_ROOT_NAME || "Documents",
    adapter,
    requestJwtSecret,
    maxDocumentBytes: parseInteger(
      "TFO_STORAGE_MAX_DOCUMENT_BYTES",
      environment.TFO_STORAGE_MAX_DOCUMENT_BYTES || "536870912",
      1,
      Number.MAX_SAFE_INTEGER,
    ),
  });
}
