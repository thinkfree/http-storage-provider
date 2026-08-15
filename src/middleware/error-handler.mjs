import { RequestAuthenticationError, StorageError } from "../domain/errors.mjs";

export function notFoundHandler(_request, response) {
  sendText(response, 404, "Not found");
}

export function errorHandler(error, _request, response, next) {
  if (response.headersSent) {
    next(error);
    return;
  }
  if (error instanceof RequestAuthenticationError) {
    sendText(response, 401, "Unauthorized");
  } else if (error instanceof StorageError) {
    sendText(response, error.statusCode, error.publicMessage);
  } else if (error?.code === "ENOENT") {
    sendText(response, 404, "Not found");
  } else if (error?.code === "ENOTEMPTY") {
    sendText(response, 409, "The directory is not empty");
  } else if (error?.code === "EACCES" || error?.code === "EPERM") {
    sendText(response, 403, "Storage access was denied");
  } else {
    console.error(
      `Storage request failed: ${error?.constructor?.name || "Error"}`,
    );
    sendText(response, 500, "Storage request failed");
  }
}

export function sendText(response, statusCode, value = "") {
  response
    .status(statusCode)
    .set("Cache-Control", "no-store")
    .type("text/plain")
    .send(value);
}
