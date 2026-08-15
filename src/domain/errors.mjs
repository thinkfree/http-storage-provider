export class StorageError extends Error {
  constructor(statusCode, publicMessage) {
    super(publicMessage);
    this.statusCode = statusCode;
    this.publicMessage = publicMessage;
  }
}

export class RequestAuthenticationError extends StorageError {
  constructor() {
    super(401, "Unauthorized");
  }
}
