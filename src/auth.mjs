import {createHmac, timingSafeEqual} from "node:crypto";
import {AuthenticationError} from "./http-error.mjs";

const JWT_TYPE = "tfo-storage-request+jwt";
const JWT_ISSUER = "thinkfree-office";
const JWT_AUDIENCE = "tfo-http-storage-provider";
const MAX_JWT_BYTES = 5120;
const MAX_LIFETIME_SECONDS = 60;

function decodeBase64Url(part) {
  if (typeof part !== "string" || !/^[A-Za-z0-9_-]+$/.test(part)) {
    throw new AuthenticationError();
  }
  const decoded = Buffer.from(part, "base64url");
  if (decoded.toString("base64url") !== part) {
    throw new AuthenticationError();
  }
  return decoded;
}

function parseJsonPart(part) {
  try {
    const value = JSON.parse(decodeBase64Url(part).toString("utf8"));
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      throw new AuthenticationError();
    }
    return value;
  } catch (error) {
    if (error instanceof AuthenticationError) throw error;
    throw new AuthenticationError();
  }
}

function constantEquals(left, right) {
  const a = Buffer.from(String(left ?? ""), "utf8");
  const b = Buffer.from(String(right ?? ""), "utf8");
  return a.length === b.length && timingSafeEqual(a, b);
}

function isOneExpectedAudience(audience) {
  if (typeof audience === "string") return audience === JWT_AUDIENCE;
  return Array.isArray(audience)
    && audience.length === 1
    && audience[0] === JWT_AUDIENCE;
}

export function verifyStorageRequest({
  token,
  secret,
  adapterHeader,
  configuredAdapter,
  method,
  rawPath,
  contentType,
  contentLength,
  contentSha256,
  nowSeconds = Math.floor(Date.now() / 1000),
}) {
  if (typeof token !== "string" || Buffer.byteLength(token, "utf8") > MAX_JWT_BYTES) {
    throw new AuthenticationError();
  }
  if (typeof secret !== "string" || Buffer.byteLength(secret, "utf8") < 32) {
    throw new AuthenticationError();
  }
  if (!constantEquals(adapterHeader, configuredAdapter)) {
    throw new AuthenticationError();
  }

  const parts = token.split(".");
  if (parts.length !== 3) throw new AuthenticationError();
  const header = parseJsonPart(parts[0]);
  const claims = parseJsonPart(parts[1]);
  if (header.alg !== "HS256" || header.typ !== JWT_TYPE) {
    throw new AuthenticationError();
  }

  const signingInput = `${parts[0]}.${parts[1]}`;
  const expectedSignature = createHmac("sha256", secret).update(signingInput).digest();
  const actualSignature = decodeBase64Url(parts[2]);
  if (expectedSignature.length !== actualSignature.length
      || !timingSafeEqual(expectedSignature, actualSignature)) {
    throw new AuthenticationError();
  }

  const request = claims.request;
  const lifetime = Number.isInteger(claims.iat) && Number.isInteger(claims.exp)
    ? claims.exp - claims.iat
    : -1;
  if (claims.iss !== JWT_ISSUER
      || !isOneExpectedAudience(claims.aud)
      || !Number.isInteger(claims.iat)
      || !Number.isInteger(claims.exp)
      || claims.iat > nowSeconds
      || claims.exp <= nowSeconds
      || lifetime <= 0
      || lifetime > MAX_LIFETIME_SECONDS
      || typeof claims.jti !== "string"
      || claims.jti.length < 1
      || claims.jti.length > 64
      || request === null
      || typeof request !== "object"
      || Array.isArray(request)
      || !constantEquals(request.adapter, configuredAdapter)
      || !constantEquals(request.method, method)
      || !constantEquals(request.path, rawPath)
      || request.content_length !== contentLength
      || !constantEquals(request.content_sha256, contentSha256)
      || !constantEquals(request.content_type ?? "", contentType ?? "")) {
    throw new AuthenticationError();
  }

  return Object.freeze({claims, request, jti: claims.jti, expiresAt: claims.exp});
}
