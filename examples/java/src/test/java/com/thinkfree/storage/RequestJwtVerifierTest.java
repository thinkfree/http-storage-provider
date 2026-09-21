package com.thinkfree.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkfree.storage.config.StorageProperties;
import com.thinkfree.storage.security.RequestAuthenticationException;
import com.thinkfree.storage.security.RequestJwtVerifier;
import com.thinkfree.storage.service.StorageStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestJwtVerifierTest {
    private static final String ADAPTER = "customer-storage-a";
    private static final String SECRET = "java-verifier-test-secret-at-least-32-bytes";
    private static final String PATH = "/prefix/tfo-storage/v1/sample%20file/info";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    private RequestJwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        var properties = new StorageProperties(root, "Documents", ADAPTER, SECRET, 1024, Set.of());
        verifier = new RequestJwtVerifier(properties, new StorageStateStore(properties, JSON));
    }

    @Test
    void rejectsUnknownMalformedAdaptersAndForgedOriginalTokens() throws Exception {
        for (Object adapter : Arrays.asList("unknown", null, 42, true, List.of(ADAPTER), Map.of("name", ADAPTER))) {
            var request = request();
            request.put("adapter", adapter);
            String token = token(request, Map.of(), Map.of(), SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
        for (Object request : Arrays.asList(null, List.of(), Map.of(), "invalid")) {
            String token = token(request, Map.of(), Map.of(), SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
        String forged = token(request(), Map.of(), Map.of(), "another-test-secret-at-least-32-bytes");
        assertThrows(RequestAuthenticationException.class, () -> verify(forged));
        assertThrows(RequestAuthenticationException.class, () -> verify("x".repeat(8193)));
    }

    @Test
    void exposesVerifiedRequestAndMetadataOnlyAfterReplayConsumption() throws Exception {
        var request = request();
        var metadata = Map.of("customer_context", "고객", "nested", Arrays.asList(true, null, Map.of("n", 1)));
        request.put("client_metadata", metadata);
        request.put("arguments", Map.of("save_type", "save"));
        String token = token(request, Map.of(), Map.of(), SECRET);
        Map<String, Object> result = verify(token);
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(request)), JSON.readTree(JSON.writeValueAsBytes(result)));
        assertThrows(RequestAuthenticationException.class, () -> verify(token));
        assertFalse(verify(token(request(), Map.of(), Map.of(), SECRET)).containsKey("client_metadata"));
    }

    @Test
    void enforcesMetadataDepthAndUtf16StringLimits() throws Exception {
        Object depth8 = "leaf";
        for (int i = 0; i < 8; i++) depth8 = Map.of("child", depth8);
        var exactBytes = new LinkedHashMap<String, Object>();
        exactBytes.put("a", "x".repeat(512));
        exactBytes.put("b", "x".repeat(512));
        exactBytes.put("c", "x".repeat(512));
        exactBytes.put("d", "x".repeat(483));
        assertEquals(2048, JSON.writeValueAsBytes(exactBytes).length);
        for (Object metadata : List.of(Map.of(), Map.of("text", "é".repeat(512)),
                Map.of("text", "😀".repeat(256)), Map.of("k".repeat(64), "ok"), Map.of("😀".repeat(32), "ok"),
                Map.of("\u00a0", "nonbreaking space is not Java whitespace"), depth8, exactBytes)) {
            var request = request();
            request.put("client_metadata", metadata);
            assertEquals(JSON.valueToTree(metadata), JSON.valueToTree(
                    verify(token(request, Map.of(), Map.of(), SECRET)).get("client_metadata")));
        }
        var oversized = new LinkedHashMap<>(exactBytes);
        oversized.put("d", "x".repeat(484));
        for (Object metadata : Arrays.asList(null, List.of(), "text", Map.of("text", "x".repeat(513)),
                Map.of("k".repeat(65), 1), Map.of("😀".repeat(33), 1), Map.of("", 1), Map.of(" \t\n\u3000", 1),
                Map.of("text", "😀".repeat(257)), Map.of("child", depth8), Map.of("text", "😀".repeat(512)))) {
            var request = request();
            request.put("client_metadata", metadata);
            String token = token(request, Map.of(), Map.of(), SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
        var request = request();
        request.put("client_metadata", oversized);
        assertNotNull(verify(token(request, Map.of(), Map.of(), SECRET)));

        // Accepted /open JSON can expand beyond 4096 bytes when Nimbus writes it.
        String input = "{\"items\":[" + String.join(",", java.util.Collections.nCopies(700, "1e-7")) + "]}";
        assertEquals(3511, input.getBytes(StandardCharsets.UTF_8).length);
        Object numericMetadata = JSON.readValue(input, Object.class);
        assertEquals(4911, JSON.writeValueAsBytes(numericMetadata).length);
        request.put("client_metadata", numericMetadata);
        String expandedToken = token(request, Map.of(), Map.of(), SECRET);
        assertTrue(expandedToken.getBytes(StandardCharsets.UTF_8).length > 5120);
        assertTrue(expandedToken.getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertEquals(JSON.valueToTree(numericMetadata), JSON.valueToTree(verify(expandedToken).get("client_metadata")));
    }

    @Test
    void preservesHeaderClaimsAndExactRequestBindings() throws Exception {
        for (var header : List.of(Map.of("alg", "HS384"), Map.of("typ", "JWT"))) {
            String token = token(request(), Map.of(), header, SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
        long now = Instant.now().getEpochSecond();
        for (var claims : List.of(Map.of("iss", "wrong"), Map.of("aud", List.of("tfo-http-storage-provider", "wrong")),
                Map.of("iat", now + 30), Map.of("exp", now - 1), Map.of("exp", now + 120), Map.of("jti", ""))) {
            String token = token(request(), claims, Map.of(), SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
        for (var change : List.of(Map.of("method", "POST"), Map.of("path", PATH.replace("%20", " ")),
                Map.of("content_length", 1), Map.of("content_length", 0.5), Map.of("content_length", false),
                Map.of("method", List.of("GET")), Map.of("content_type", " "),
                java.util.Collections.singletonMap("content_type", null),
                Map.of("content_sha256", "0".repeat(64)), Map.of("content_type", "application/json"))) {
            var request = request();
            request.putAll(change);
            String token = token(request, Map.of(), Map.of(), SECRET);
            assertThrows(RequestAuthenticationException.class, () -> verify(token));
        }
    }

    private Map<String, Object> verify(String token) throws Exception {
        return verifier.verify(token, "GET", PATH, null, 0, emptyHash());
    }

    private static Map<String, Object> request() throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("adapter", ADAPTER);
        request.put("method", "GET");
        request.put("path", PATH);
        request.put("content_length", 0);
        request.put("content_sha256", emptyHash());
        return request;
    }

    private static String emptyHash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
    }

    private static String token(Object request, Map<String, ?> overrides, Map<String, ?> headerOverrides,
                                String secret) throws Exception {
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "HS256");
        header.put("typ", "tfo-storage-request+jwt");
        header.putAll(headerOverrides);
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "thinkfree-office");
        claims.put("aud", "tfo-http-storage-provider");
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + 60);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("request", request);
        claims.putAll(overrides);
        // Extra whitespace proves verification uses the original signing input.
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + encoder.encodeToString((" " + JSON.writeValueAsString(claims) + " ").getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }
}
