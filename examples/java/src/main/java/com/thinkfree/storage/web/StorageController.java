package com.thinkfree.storage.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkfree.storage.config.StorageProperties;
import com.thinkfree.storage.security.RequestJwtVerifier;
import com.thinkfree.storage.service.LocalDirectoryStorageService;
import com.thinkfree.storage.service.StorageException;
import com.thinkfree.storage.service.StorageStateStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Spring MVC boundary for the signed HTTP Storage protocol. */
@RestController
public class StorageController {
    private static final int MAX_METADATA_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final StorageProperties properties;
    private final StorageRouteParser routeParser;
    private final RequestJwtVerifier requestVerifier;
    private final LocalDirectoryStorageService storageService;
    private final StorageStateStore stateStore;
    private final ObjectMapper objectMapper;

    public StorageController(
            StorageProperties properties,
            StorageRouteParser routeParser,
            RequestJwtVerifier requestVerifier,
            LocalDirectoryStorageService storageService,
            StorageStateStore stateStore,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.routeParser = routeParser;
        this.requestVerifier = requestVerifier;
        this.storageService = storageService;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return noStore(ResponseEntity.ok()).body("ok\n");
    }

    /*
     * One protocol entry point is intentional: the signed JWT contains the raw
     * encoded path and body digest, so routing must happen after those exact
     * request values are captured rather than after MVC path normalization.
     */
    @RequestMapping(
            value = "/tfo-storage/v1/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE}
    )
    public ResponseEntity<?> handle(HttpServletRequest request) throws Exception {
        StorageRoute route = routeParser.parse(request);
        RequestBody requestBody = readRequestBody(request, route.operation());
        try {
            requestVerifier.verify(
                    request.getHeader("X-TFO-Storage-Request-JWT"),
                    request.getHeader("X-TFO-Storage-Adapter"),
                    request.getMethod(),
                    route.rawPath(),
                    request.getHeader(HttpHeaders.CONTENT_TYPE),
                    requestBody.length(),
                    requestBody.sha256()
            );
            // Do not move capability selection above verification. Even an
            // unsupported route authenticates the complete signed request,
            // and PUT_NOT_SUPPORTED must not touch the destination document.
            if (properties.unsupportedOperations().contains(
                    route.operation().name().toLowerCase(java.util.Locale.ROOT))) {
                return operationNotSupported(route.operation());
            }
            return execute(route, requestBody);
        } finally {
            requestBody.close();
        }
    }

    private ResponseEntity<?> operationNotSupported(StorageOperation operation) throws IOException {
        // CloudOffice accepts only 501 application/json with this exact
        // operation-specific single-field object as a capability declaration.
        return jsonResponse(HttpStatus.NOT_IMPLEMENTED,
                java.util.Map.of("code", operation.name() + "_NOT_SUPPORTED"));
    }

    private ResponseEntity<?> execute(StorageRoute route, RequestBody body) throws IOException {
        return switch (route.operation()) {
            case INFO -> jsonResponse(HttpStatus.OK, storageService.info(route.path()));
            case LIST -> jsonResponse(HttpStatus.OK, storageService.list(route.path()));
            case GET -> download(route);
            case PUT -> {
                String revision = storageService.save(route.path(), body.stagedFile());
                body.markCommitted();
                yield noStore(ResponseEntity.ok())
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(revision);
            }
            case LOCK -> {
                storageService.lock(route.path(), lockRequest(body.bytes()).owner());
                yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
            case UNLOCK -> {
                storageService.unlock(route.path(), lockRequest(body.bytes()).owner());
                yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
            case MKDIR -> {
                storageService.createDirectory(route.path(), nameRequest(body.bytes()).name());
                yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
            case RENAME -> {
                storageService.rename(route.path(), nameRequest(body.bytes()).name());
                yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
            case DELETE -> {
                storageService.delete(route.path());
                yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
        };
    }

    private ResponseEntity<Resource> download(StorageRoute route) throws IOException {
        LocalDirectoryStorageService.Download download = storageService.download(route.path());
        return noStore(ResponseEntity.ok())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.contentLength())
                .body(download.resource());
    }

    private ResponseEntity<byte[]> jsonResponse(HttpStatus status, Object value) throws IOException {
        // Spring's object converter may stream JSON without publishing a fixed
        // response length. Rendering this bounded metadata once makes the
        // protocol's exact Content-Length explicit for INFO and LIST.
        byte[] body = objectMapper.writeValueAsBytes(value);
        if (body.length > MAX_METADATA_RESPONSE_BYTES) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The metadata response exceeds the 5 MiB protocol limit");
        }
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    private RequestBody readRequestBody(HttpServletRequest request, StorageOperation operation)
            throws Exception {
        return switch (operation.bodyKind()) {
            case NONE -> readEmptyBody(request);
            case JSON -> readJsonBody(request);
            case DOCUMENT -> stageDocument(request);
        };
    }

    private RequestBody readEmptyBody(HttpServletRequest request) {
        rejectTransferEncoding(request);
        if (contentLength(request, false) != 0) {
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "This operation does not accept a request body");
        }
        return RequestBody.empty();
    }

    private RequestBody readJsonBody(HttpServletRequest request) throws Exception {
        requireContentType(request, MediaType.APPLICATION_JSON_VALUE);
        rejectTransferEncoding(request);
        long declaredLength = contentLength(request, true);
        if (declaredLength > 16 * 1024) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large");
        }
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length != declaredLength) {
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "The request body does not match Content-Length");
        }
        return RequestBody.json(bytes, sha256(bytes));
    }

    private RequestBody stageDocument(HttpServletRequest request) throws Exception {
        requireContentType(request, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        rejectTransferEncoding(request);
        long declaredLength = contentLength(request, true);
        if (declaredLength > properties.maxDocumentBytes()) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The document exceeds the Provider size limit");
        }

        Path stagingFile = stateStore.createStagingFile();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        try (InputStream input = request.getInputStream(); OutputStream output = Files.newOutputStream(stagingFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                written += read;
                if (written > declaredLength || written > properties.maxDocumentBytes()) {
                    throw new StorageException(HttpStatus.BAD_REQUEST,
                            "The request body does not match Content-Length");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(stagingFile);
            throw exception;
        }
        if (written != declaredLength) {
            Files.deleteIfExists(stagingFile);
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "The request body does not match Content-Length");
        }
        return RequestBody.document(
                written, HexFormat.of().formatHex(digest.digest()), stagingFile);
    }

    private LockRequest lockRequest(byte[] bytes) {
        try {
            LockRequest value = objectMapper.readValue(bytes, LockRequest.class);
            requireExactObject(bytes, "owner");
            if (value.owner() == null || value.owner().isBlank()) throw invalidJsonBody("owner");
            return value;
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidJsonBody("owner");
        }
    }

    private NameRequest nameRequest(byte[] bytes) {
        try {
            NameRequest value = objectMapper.readValue(bytes, NameRequest.class);
            requireExactObject(bytes, "name");
            if (value.name() == null || value.name().isBlank()) throw invalidJsonBody("name");
            return value;
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidJsonBody("name");
        }
    }

    private void requireExactObject(byte[] bytes, String field) throws IOException {
        JsonNode node = objectMapper.readTree(bytes);
        if (node == null || !node.isObject() || node.size() != 1 || !node.has(field)) {
            throw invalidJsonBody(field);
        }
    }

    private static StorageException invalidJsonBody(String field) {
        return new StorageException(HttpStatus.BAD_REQUEST,
                "The request body must contain only a non-empty " + field + " string");
    }

    private static void requireContentType(HttpServletRequest request, String expected) {
        if (!expected.equals(request.getHeader(HttpHeaders.CONTENT_TYPE))) {
            throw new StorageException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "This operation requires " + expected);
        }
    }

    private static long contentLength(HttpServletRequest request, boolean required) {
        String value = request.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (value == null) {
            if (required) {
                throw new StorageException(HttpStatus.LENGTH_REQUIRED, "Content-Length is required");
            }
            return 0;
        }
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException exception) {
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "Content-Length must be a non-negative integer");
        }
    }

    private static void rejectTransferEncoding(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "Chunked request bodies are not supported");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore());
    }

    private static final class RequestBody implements AutoCloseable {
        private final long length;
        private final String sha256;
        private final byte[] bytes;
        private final Path stagedFile;
        private boolean committed;

        private RequestBody(long length, String sha256, byte[] bytes, Path stagedFile) {
            this.length = length;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.stagedFile = stagedFile;
        }

        static RequestBody empty() { return new RequestBody(0, EMPTY_SHA256, null, null); }
        static RequestBody json(byte[] bytes, String sha256) {
            return new RequestBody(bytes.length, sha256, bytes, null);
        }
        static RequestBody document(long length, String sha256, Path file) {
            return new RequestBody(length, sha256, null, file);
        }

        long length() { return length; }
        String sha256() { return sha256; }
        byte[] bytes() { return bytes; }
        Path stagedFile() { return stagedFile; }
        void markCommitted() { committed = true; }

        @Override
        public void close() throws IOException {
            if (stagedFile != null && !committed) Files.deleteIfExists(stagedFile);
        }
    }
}
