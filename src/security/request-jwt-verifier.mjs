import { createHmac, timingSafeEqual } from "node:crypto";
import { RequestAuthenticationError } from "../domain/errors.mjs";

const JWT_TYPE = "tfo-storage-request+jwt";
const JWT_ISSUER = "thinkfree-office";
const JWT_AUDIENCE = "tfo-http-storage-provider";

export class RequestJwtVerifier {
  constructor(config, stateStore) {
    this.config = config;
    this.stateStore = stateStore;
  }

  // Returns the signed request only after verification and replay consumption.
  async verify(request, route, body) {
    const token = oneHeader(request, "x-tfo-storage-request-jwt");
    if (typeof token !== "string" || Buffer.byteLength(token, "utf8") > 8192) {
      throw new RequestAuthenticationError();
    }
    const parts = token.split(".");
    if (parts.length !== 3) throw new RequestAuthenticationError();
    const header = parseJsonPart(parts[0]);
    const claims = parseJsonPart(parts[1]);
    // Unverified input selects only an existing configured key, never authority.
    const adapter = claims.request?.adapter;
    if (typeof adapter !== "string" || adapter !== this.config.adapter) {
      throw new RequestAuthenticationError();
    }
    if (header.alg !== "HS256" || header.typ !== JWT_TYPE) {
      throw new RequestAuthenticationError();
    }
    const expectedSignature = createHmac("sha256", this.config.requestJwtSecret)
      .update(`${parts[0]}.${parts[1]}`)
      .digest();
    const actualSignature = decodeBase64Url(parts[2]);
    if (
      expectedSignature.length !== actualSignature.length ||
      !timingSafeEqual(expectedSignature, actualSignature)
    ) {
      throw new RequestAuthenticationError();
    }

    const signedRequest = claims.request;
    const now = Math.floor(Date.now() / 1000);
    const lifetime =
      Number.isInteger(claims.iat) && Number.isInteger(claims.exp)
        ? claims.exp - claims.iat
        : -1;
    if (
      claims.iss !== JWT_ISSUER ||
      !expectedAudience(claims.aud) ||
      !Number.isInteger(claims.iat) ||
      !Number.isInteger(claims.exp) ||
      claims.iat > now ||
      claims.exp <= now ||
      lifetime <= 0 ||
      lifetime > 60 ||
      typeof claims.jti !== "string" ||
      claims.jti.length < 1 ||
      claims.jti.length > 64 ||
      signedRequest === null ||
      typeof signedRequest !== "object" ||
      Array.isArray(signedRequest) ||
      !constantEquals(signedRequest.adapter, this.config.adapter) ||
      !constantEquals(signedRequest.method, request.method) ||
      !constantEquals(signedRequest.path, route.rawPath) ||
      signedRequest.content_length !== body.length ||
      (Object.hasOwn(signedRequest, "content_type") &&
        typeof signedRequest.content_type !== "string") ||
      !constantEquals(signedRequest.content_sha256, body.sha256) ||
      !constantEquals(
        signedRequest.content_type ?? "",
        oneHeader(request, "content-type") ?? "",
      )
    ) {
      throw new RequestAuthenticationError();
    }
    if (Object.hasOwn(signedRequest, "client_metadata")) {
      validateMetadata(signedRequest.client_metadata);
    }
    await this.stateStore.consumeRequestId(claims.jti, claims.exp);
    // Transit integrity is not customer authorization. Only expose verified data.
    return signedRequest;
  }
}

function validateMetadata(metadata) {
  // TFO caps the /open JSON input at 4096 bytes, not its JWT reserialization.
  // The incoming JWT is capped at 8192 bytes above; root depth 0, max depth 8.
  // Match TFO's Java String.length(): values 512, nonblank keys 64 UTF-16 units.
  if (
    metadata === null ||
    typeof metadata !== "object" ||
    Array.isArray(metadata)
  )
    throw new RequestAuthenticationError();
  function visit(value, depth) {
    if (depth > 8 || (typeof value === "string" && value.length > 512)) {
      throw new RequestAuthenticationError();
    }
    if (value !== null && typeof value === "object") {
      for (const [key, child] of Object.entries(value)) {
        // Java String.isBlank()/Character.isWhitespace, not JavaScript trim().
        if (
          key.length > 64 ||
          /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(
            key,
          )
        ) {
          throw new RequestAuthenticationError();
        }
        visit(child, depth + 1);
      }
    }
  }
  visit(metadata, 0);
}

export function oneHeader(request, name) {
  const value = request.headers[name];
  return typeof value === "string" ? value : null;
}

function decodeBase64Url(part) {
  if (typeof part !== "string" || !/^[A-Za-z0-9_-]+$/.test(part)) {
    throw new RequestAuthenticationError();
  }
  const decoded = Buffer.from(part, "base64url");
  if (decoded.toString("base64url") !== part)
    throw new RequestAuthenticationError();
  return decoded;
}

function parseJsonPart(part) {
  try {
    const value = JSON.parse(decodeBase64Url(part).toString("utf8"));
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      throw new RequestAuthenticationError();
    }
    return value;
  } catch (error) {
    if (error instanceof RequestAuthenticationError) throw error;
    throw new RequestAuthenticationError();
  }
}

function constantEquals(left, right) {
  if (typeof left !== "string" || typeof right !== "string") return false;
  const a = Buffer.from(left, "utf8");
  const b = Buffer.from(right, "utf8");
  return a.length === b.length && timingSafeEqual(a, b);
}

function expectedAudience(value) {
  return (
    value === JWT_AUDIENCE ||
    (Array.isArray(value) && value.length === 1 && value[0] === JWT_AUDIENCE)
  );
}
