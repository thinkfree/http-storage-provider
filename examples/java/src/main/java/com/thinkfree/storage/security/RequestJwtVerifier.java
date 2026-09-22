package com.thinkfree.storage.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thinkfree.storage.config.StorageProperties;
import com.thinkfree.storage.service.StorageStateStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Verifies a signed Office request against the actual Servlet request body. */
@Component
public class RequestJwtVerifier {
    private static final JOSEObjectType TOKEN_TYPE = new JOSEObjectType("tfo-storage-request+jwt");
    private static final String ISSUER = "thinkfree-office";
    private static final String AUDIENCE = "tfo-http-storage-provider";

    private final StorageProperties properties;
    private final StorageStateStore stateStore;

    public RequestJwtVerifier(StorageProperties properties, StorageStateStore stateStore) {
        this.properties = properties;
        this.stateStore = stateStore;
    }

    /** Returns the signed request only after verification and replay consumption. */
    public Map<String, Object> verify(
            String token,
            String method,
            String rawPath,
            String contentType,
            long contentLength,
            String contentSha256
    ) {
        try {
            require(token != null && token.getBytes(StandardCharsets.UTF_8).length <= 8_192);
            SignedJWT jwt = SignedJWT.parse(token);
            // Unverified input selects only an existing configured key, never authority.
            Map<String, Object> candidate = jwt.getJWTClaimsSet().getJSONObjectClaim("request");
            require(candidate != null && candidate.get("adapter") instanceof String);
            require(constantEquals(candidate.get("adapter"), properties.adapter()));
            require(JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()));
            require(TOKEN_TYPE.equals(jwt.getHeader().getType()));
            require(jwt.verify(new MACVerifier(properties.requestJwtSecret().getBytes(StandardCharsets.UTF_8))));

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Map<String, Object> rawClaims = jwt.getPayload().toJSONObject();
            require(isInteger(rawClaims.get("iat")) && isInteger(rawClaims.get("exp")));
            Instant now = Instant.now();
            Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
            Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            Map<String, Object> signedRequest = claims.getJSONObjectClaim("request");
            require(ISSUER.equals(claims.getIssuer()));
            require(List.of(AUDIENCE).equals(claims.getAudience()));
            require(issuedAt != null && expiresAt != null);
            require(!issuedAt.isAfter(now) && expiresAt.isAfter(now));
            require(expiresAt.isAfter(issuedAt) && !expiresAt.isAfter(issuedAt.plusSeconds(60)));
            require(claims.getJWTID() != null && !claims.getJWTID().isBlank()
                    && claims.getJWTID().length() <= 64);
            require(signedRequest != null);
            require(constantEquals(properties.adapter(), signedRequest.get("adapter")));
            require(constantEquals(method, signedRequest.get("method")));
            require(constantEquals(rawPath, signedRequest.get("path")));
            require(isInteger(signedRequest.get("content_length")));
            require(((Number) signedRequest.get("content_length")).longValue() == contentLength);
            require(constantEquals(contentSha256, signedRequest.get("content_sha256")));
            Object signedContentType = signedRequest.getOrDefault("content_type", "");
            require(signedContentType instanceof String);
            require(constantEquals(contentType == null ? "" : contentType, signedContentType));
            if (signedRequest.containsKey("client_metadata")) {
                Object metadata = signedRequest.get("client_metadata");
                require(metadata instanceof Map);
                validateMetadata(metadata, 0);
            }
            stateStore.consumeRequestId(claims.getJWTID(), expiresAt);
            // Verified transit integrity does not confer customer authorization.
            return signedRequest;
        } catch (RequestAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RequestAuthenticationException();
        }
    }

    private static boolean isInteger(Object value) {
        return value instanceof Long || value instanceof Integer;
    }

    private static void validateMetadata(Object value, int depth) {
        // TFO caps /open JSON input at 4096 bytes, not its JWT reserialization.
        // Incoming JWT is capped at 8192 bytes above; root depth 0, max depth 8.
        // Match TFO: values 512, nonblank keys 64 UTF-16 units (String.length()).
        require(depth <= 8);
        if (value instanceof String text) {
            require(text.length() <= 512);
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = (String) entry.getKey();
                require(!key.isBlank() && key.length() <= 64);
                validateMetadata(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) validateMetadata(child, depth + 1);
        }
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean constantEquals(Object left, Object right) {
        byte[] a = normalize(left).getBytes(StandardCharsets.UTF_8);
        byte[] b = normalize(right).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static void require(boolean condition) {
        if (!condition) throw new RequestAuthenticationException();
    }
}
