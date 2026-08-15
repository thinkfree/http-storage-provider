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

    public void verify(
            String token,
            String adapterHeader,
            String method,
            String rawPath,
            String contentType,
            long contentLength,
            String contentSha256
    ) {
        try {
            require(token != null && token.getBytes(StandardCharsets.UTF_8).length <= 5_120);
            require(constantEquals(adapterHeader, properties.adapter()));
            SignedJWT jwt = SignedJWT.parse(token);
            require(JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()));
            require(TOKEN_TYPE.equals(jwt.getHeader().getType()));
            require(jwt.verify(new MACVerifier(properties.requestJwtSecret().getBytes(StandardCharsets.UTF_8))));

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
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
            require(signedRequest.get("content_length") instanceof Number);
            require(((Number) signedRequest.get("content_length")).longValue() == contentLength);
            require(constantEquals(contentSha256, signedRequest.get("content_sha256")));
            require(constantEquals(normalize(contentType), normalize(signedRequest.get("content_type"))));
            stateStore.consumeRequestId(claims.getJWTID(), expiresAt);
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
}
