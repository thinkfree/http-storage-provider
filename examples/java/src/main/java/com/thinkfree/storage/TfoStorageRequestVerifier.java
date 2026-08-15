package com.thinkfree.storage;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TfoStorageRequestVerifier {
    private static final String TYPE = "tfo-storage-request+jwt";
    private static final String ISSUER = "thinkfree-office";
    private static final String AUDIENCE = "tfo-http-storage-provider";

    public VerifiedRequest verify(
            String token,
            String secret,
            String adapterHeader,
            String configuredAdapter,
            String method,
            String rawPath,
            String contentType,
            long contentLength,
            String contentSha256,
            ReplayStore replayStore
    ) {
        try {
            require(token != null && token.getBytes(StandardCharsets.UTF_8).length <= 5_120);
            require(secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32);
            require(constantEquals(adapterHeader, configuredAdapter));
            SignedJWT jwt = SignedJWT.parse(token);
            require(JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()));
            require(new JOSEObjectType(TYPE).equals(jwt.getHeader().getType()));
            require(jwt.verify(new MACVerifier(secret.getBytes(StandardCharsets.UTF_8))));

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
            Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            Map<String, Object> request = claims.getJSONObjectClaim("request");
            require(ISSUER.equals(claims.getIssuer()));
            require(List.of(AUDIENCE).equals(claims.getAudience()));
            require(issuedAt != null && expiresAt != null);
            require(!issuedAt.isAfter(now) && expiresAt.isAfter(now));
            require(expiresAt.isAfter(issuedAt) && !expiresAt.isAfter(issuedAt.plusSeconds(60)));
            require(claims.getJWTID() != null && !claims.getJWTID().isBlank()
                    && claims.getJWTID().length() <= 64);
            require(request != null);
            require(constantEquals(configuredAdapter, request.get("adapter")));
            require(constantEquals(method, request.get("method")));
            require(constantEquals(rawPath, request.get("path")));
            require(request.get("content_length") instanceof Number);
            require(((Number) request.get("content_length")).longValue() == contentLength);
            require(constantEquals(contentSha256, request.get("content_sha256")));
            require(constantEquals(normalize(contentType), normalize(request.get("content_type"))));
            replayStore.consume(claims.getJWTID(), expiresAt);
            return new VerifiedRequest(Map.copyOf(request), claims.getJWTID(), expiresAt);
        } catch (RequestAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RequestAuthenticationException();
        }
    }

    private static String normalize(Object value) {
        return value == null || value.toString().isBlank() ? "" : value.toString();
    }

    private static boolean constantEquals(Object left, Object right) {
        byte[] a = normalize(left).getBytes(StandardCharsets.UTF_8);
        byte[] b = normalize(right).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static void require(boolean condition) {
        if (!condition) throw new RequestAuthenticationException();
    }

    @FunctionalInterface
    public interface ReplayStore {
        void consume(String requestId, Instant expiresAt);
    }

    public record VerifiedRequest(Map<String, Object> request, String requestId, Instant expiresAt) {}

    public static final class RequestAuthenticationException extends SecurityException {
        public RequestAuthenticationException() {
            super("Unauthorized");
        }
    }
}
