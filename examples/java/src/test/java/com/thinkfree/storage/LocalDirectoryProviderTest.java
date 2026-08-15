package com.thinkfree.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalDirectoryProviderTest {
    private static final String ADAPTER = "customer-storage-a";
    private static final String SECRET = "java-provider-test-secret-at-least-32-bytes";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private Path storageRoot;
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = temporaryDirectory.resolve("storage");
        Files.createDirectories(storageRoot.resolve("contracts"));
        Files.writeString(storageRoot.resolve("contracts/sample document.docx"), "original");
        application = new SpringApplicationBuilder(ProviderApplication.class).run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--tfo.storage.root=" + storageRoot,
                "--tfo.storage.root-name=Documents",
                "--tfo.storage.adapter=" + ADAPTER,
                "--tfo.storage.request-jwt-secret=" + SECRET,
                "--tfo.storage.max-document-bytes=1048576"
        );
        client = HttpClient.newHttpClient();
        int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        application.close();
    }

    @Test
    void servesTheCompleteStorageLifecycle() throws Exception {
        String file = "contracts/sample%20document.docx";

        HttpResponse<byte[]> info = send("GET", "/tfo-storage/v1/" + file + "/info", null, null, null);
        assertEquals(200, info.statusCode());
        JsonNode entry = JSON.readTree(info.body());
        assertEquals("contracts/sample document.docx", entry.get("path").textValue());
        assertEquals(8, entry.get("size").longValue());
        assertEquals("file", entry.get("type").textValue());

        HttpResponse<byte[]> list = send("GET", "/tfo-storage/v1/contracts/list", null, null, null);
        assertEquals(200, list.statusCode());
        assertEquals("sample document.docx",
                JSON.readTree(list.body()).get("entries").get(0).get("name").textValue());

        HttpResponse<byte[]> get = send("GET", "/tfo-storage/v1/" + file + "/get", null, null, null);
        assertEquals(200, get.statusCode());
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), get.body());
        assertEquals("8", get.headers().firstValue("Content-Length").orElseThrow());

        byte[] lockBody = "{\"owner\":\"office-runtime-1\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(204, send("POST", "/tfo-storage/v1/" + file + "/lock",
                lockBody, "application/json", null).statusCode());

        byte[] saved = "saved-document".getBytes(StandardCharsets.UTF_8);
        assertEquals(200, send("PUT", "/tfo-storage/v1/" + file + "/put",
                saved, "application/octet-stream", null).statusCode());
        assertEquals("saved-document", Files.readString(storageRoot.resolve("contracts/sample document.docx")));

        assertEquals(204, send("POST", "/tfo-storage/v1/" + file + "/unlock",
                lockBody, "application/json", null).statusCode());
        assertEquals(204, send("POST", "/tfo-storage/v1/contracts/mkdir",
                "{\"name\":\"archive\"}".getBytes(StandardCharsets.UTF_8), "application/json", null).statusCode());
        assertEquals(204, send("POST", "/tfo-storage/v1/" + file + "/rename",
                "{\"name\":\"renamed.docx\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", null).statusCode());
        assertEquals(204, send("DELETE", "/tfo-storage/v1/contracts/renamed.docx/delete",
                null, null, null).statusCode());
        assertEquals(204, send("DELETE", "/tfo-storage/v1/contracts/archive/delete",
                null, null, null).statusCode());
        assertFalse(Files.exists(storageRoot.resolve("contracts/renamed.docx")));
    }

    @Test
    void rejectsReplayAndPathTraversal() throws Exception {
        String path = "/tfo-storage/v1/contracts/sample%20document.docx/info";
        String token = token("GET", path, new byte[0], null);
        assertEquals(200, send("GET", path, null, null, token).statusCode());
        assertEquals(401, send("GET", path, null, null, token).statusCode());
        assertEquals(400, send("GET", "/tfo-storage/v1/%2E%2E/info", null, null, null).statusCode());
        assertEquals(400, send("DELETE", "/tfo-storage/v1/delete", null, null, null).statusCode());
    }

    private HttpResponse<byte[]> send(
            String method,
            String rawPath,
            byte[] body,
            String contentType,
            String explicitToken
    ) throws Exception {
        byte[] actualBody = body == null ? new byte[0] : body;
        String requestToken = explicitToken == null
                ? token(method, rawPath, actualBody, contentType)
                : explicitToken;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + rawPath))
                .header("X-TFO-Storage-Adapter", ADAPTER)
                .header("X-TFO-Storage-Request-JWT", requestToken);
        if (contentType != null) builder.header("Content-Type", contentType);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String token(String method, String path, byte[] body, String contentType) throws Exception {
        Instant now = Instant.now();
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("adapter", ADAPTER);
        request.put("method", method);
        request.put("path", path);
        request.put("content_length", body.length);
        request.put("content_sha256", sha256(body));
        if (contentType != null) request.put("content_type", contentType);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(new JOSEObjectType("tfo-storage-request+jwt"))
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer("thinkfree-office")
                        .audience(List.of("tfo-http-storage-provider"))
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(60)))
                        .jwtID(UUID.randomUUID().toString())
                        .claim("request", request)
                        .build()
        );
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
