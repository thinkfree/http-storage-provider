export class HttpError extends Error {
  constructor(status, publicMessage) {
    super(publicMessage);
    this.status = status;
    this.publicMessage = publicMessage;
  }
}

export class AuthenticationError extends HttpError {
  constructor() {
    super(401, "Unauthorized");
  }
}
