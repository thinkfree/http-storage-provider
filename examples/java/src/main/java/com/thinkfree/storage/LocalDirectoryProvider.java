package com.thinkfree.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Complete TFO HTTP Storage Provider example backed by one local directory.
 *
 * <p>The local filesystem keeps this example focused on the wire contract.
 * A production Provider can replace these filesystem operations with S3, a
 * database, or another document store while preserving the HTTP contract.</p>
 */
public final class LocalDirectoryProvider implements AutoCloseable {
    private static final String PREFIX = "/tfo-storage/v1";
    private static final String STATE_DIRECTORY = ".tfo-http-storage-state";
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Set<String> OPERATIONS = Set.of(
            "info", "list", "get", "put", "lock", "unlock", "mkdir", "rename", "delete");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderConfig config;
    private final ProviderStateStore state;
    private final HttpServer server;
    private final ExecutorService executor;
    private final TfoStorageRequestVerifier verifier = new TfoStorageRequestVerifier();

    private LocalDirectoryProvider(ProviderConfig config) throws IOException {
        this.config = config;
        Files.createDirectories(config.storageRoot());
        this.state = new ProviderStateStore(config.storageRoot(), config.adapter());
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public static LocalDirectoryProvider start(ProviderConfig config) throws IOException {
        LocalDirectoryProvider provider = new LocalDirectoryProvider(config);
        provider.server.start();
        return provider;
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        StagedBody staged = null;
        try {
            if ("GET".equals(exchange.getRequestMethod()) && "/healthz".equals(exchange.getRequestURI().getRawPath())) {
                sendText(exchange, 200, "ok\n");
                return;
            }

            Route route = parseRoute(exchange);
            Body body;
            if ("put".equals(route.operation())) {
                requireContentType(exchange, "application/octet-stream");
                staged = stageBody(exchange);
                body = staged;
            } else if (Set.of("lock", "unlock", "mkdir", "rename").contains(route.operation())) {
                requireContentType(exchange, "application/json");
                body = readBody(exchange, 16 * 1024);
            } else {
                rejectTransferEncoding(exchange);
                if (contentLength(exchange, false) != 0) {
                    throw new ProviderException(400, "This operation does not accept a request body");
                }
                body = new Body(0, EMPTY_SHA256, null);
            }

            TfoStorageRequestVerifier.VerifiedRequest verified = verifier.verify(
                    header(exchange, "X-TFO-Storage-Request-JWT"),
                    config.requestJwtSecret(),
                    header(exchange, "X-TFO-Storage-Adapter"),
                    config.adapter(),
                    exchange.getRequestMethod(),
                    route.rawPath(),
                    header(exchange, "Content-Type"),
                    body.length(),
                    body.sha256(),
                    state::consumeJti
            );
            execute(exchange, route, body, verified);
            if (staged != null && staged.committed) staged = null;
        } catch (TfoStorageRequestVerifier.RequestAuthenticationException exception) {
            sendText(exchange, 401, "Unauthorized");
        } catch (ProviderException exception) {
            sendText(exchange, exception.status, exception.getMessage());
        } catch (NoSuchFileException exception) {
            sendText(exchange, 404, "Not found");
        } catch (java.nio.file.DirectoryNotEmptyException exception) {
            sendText(exchange, 409, "The directory is not empty");
        } catch (java.nio.file.AccessDeniedException exception) {
            sendText(exchange, 403, "Storage access was denied");
        } catch (Exception exception) {
            System.err.println("Storage request failed: " + exception.getClass().getSimpleName());
            sendText(exchange, 500, "Storage request failed");
        } finally {
            if (staged != null) Files.deleteIfExists(staged.file);
            exchange.close();
        }
    }

    private void execute(
            HttpExchange exchange,
            Route route,
            Body body,
            TfoStorageRequestVerifier.VerifiedRequest verified
    ) throws Exception {
        switch (route.operation()) {
            case "info" -> sendJson(exchange, 200, entry(route.segments()));
            case "list" -> {
                Path directory = requireDirectory(route.segments());
                List<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                        child -> !STATE_DIRECTORY.equals(child.getFileName().toString()))) {
                    for (Path child : stream) children.add(child);
                }
                children.sort(Comparator.comparing(child -> child.getFileName().toString()));
                if (children.size() > 10_000) {
                    throw new ProviderException(413, "The directory contains more than 10000 entries");
                }
                List<StorageEntry> entries = new ArrayList<>();
                for (Path child : children) {
                    List<String> segments = new ArrayList<>(route.segments());
                    segments.add(child.getFileName().toString());
                    entries.add(entry(segments));
                }
                sendJson(exchange, 200, Map.of("entries", entries));
            }
            case "get" -> {
                Path file = safeExistingPath(route.segments());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ProviderException(409, "The requested item is not a file");
                }
                long size = Files.size(file);
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/octet-stream");
                headers.set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, size);
                try (InputStream input = Files.newInputStream(file); OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            }
            case "put" -> {
                if (route.segments().isEmpty()) throw new ProviderException(400, "A document path is required");
                requireDirectory(route.segments().subList(0, route.segments().size() - 1));
                Path target = safePath(route.segments(), true);
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ProviderException(409, "A directory already uses this path");
                }
                StagedBody upload = (StagedBody) body;
                try {
                    Files.move(upload.file, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(upload.file, target, StandardCopyOption.REPLACE_EXISTING);
                }
                upload.committed = true;
                sendText(exchange, 200, revision(Files.readAttributes(target, BasicFileAttributes.class)));
            }
            case "lock" -> {
                String owner = singleString(body.bytes(), "owner");
                entry(route.segments());
                state.acquireLock(route.documentPath(), owner);
                sendEmpty(exchange, 204);
            }
            case "unlock" -> {
                state.releaseLock(route.documentPath(), singleString(body.bytes(), "owner"));
                sendEmpty(exchange, 204);
            }
            case "mkdir" -> {
                String name = childName(singleString(body.bytes(), "name"));
                Path parent = requireDirectory(route.segments());
                try {
                    Files.createDirectory(parent.resolve(name));
                } catch (FileAlreadyExistsException exception) {
                    throw new ProviderException(409, "An item already uses this name");
                }
                sendEmpty(exchange, 204);
            }
            case "rename" -> {
                if (route.segments().isEmpty()) {
                    throw new ProviderException(400, "The storage root cannot be renamed");
                }
                state.requireUnlocked(route.documentPath());
                String name = childName(singleString(body.bytes(), "name"));
                Path source = safeExistingPath(route.segments());
                Path target = source.getParent().resolve(name);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ProviderException(409, "An item already uses this name");
                }
                Files.move(source, target);
                sendEmpty(exchange, 204);
            }
            case "delete" -> {
                if (route.segments().isEmpty()) {
                    throw new ProviderException(400, "The storage root cannot be deleted");
                }
                state.requireUnlocked(route.documentPath());
                Files.delete(safeExistingPath(route.segments()));
                sendEmpty(exchange, 204);
            }
            default -> throw new ProviderException(404, "Unknown storage operation");
        }
    }

    private StorageEntry entry(List<String> segments) throws IOException {
        Path file = segments.isEmpty() ? config.storageRoot().toRealPath() : safeExistingPath(segments);
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() && !attributes.isDirectory()) {
            throw new ProviderException(403, "This storage item type is not supported");
        }
        String documentPath = String.join("/", segments);
        LockRecord lock = state.currentLock(documentPath);
        return new StorageEntry(
                documentPath,
                segments.isEmpty() ? config.rootName() : segments.get(segments.size() - 1),
                attributes.isDirectory() ? "directory" : "file",
                attributes.isDirectory() ? 0 : attributes.size(),
                Files.isReadable(file),
                Files.isWritable(file),
                lock != null,
                lock == null ? null : lock.owner(),
                attributes.creationTime().toInstant().toString(),
                attributes.lastModifiedTime().toInstant().toString(),
                revision(attributes)
        );
    }

    private Route parseRoute(HttpExchange exchange) {
        if (exchange.getRequestURI().getRawQuery() != null) {
            throw new ProviderException(400, "Query strings are not supported");
        }
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!rawPath.startsWith(PREFIX + "/")) throw new ProviderException(404, "Not found");
        String[] rawSegments = rawPath.substring(PREFIX.length() + 1).split("/", -1);
        for (String segment : rawSegments) {
            if (segment.isEmpty()) throw new ProviderException(400, "Empty path segments are not supported");
        }
        String operation = rawSegments[rawSegments.length - 1];
        if (!OPERATIONS.contains(operation)) throw new ProviderException(404, "Unknown storage operation");
        String expectedMethod = Map.of(
                "info", "GET", "list", "GET", "get", "GET", "put", "PUT",
                "lock", "POST", "unlock", "POST", "mkdir", "POST", "rename", "POST", "delete", "DELETE"
        ).get(operation);
        if (!expectedMethod.equals(exchange.getRequestMethod())) {
            throw new ProviderException(405, "Method not allowed");
        }
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < rawSegments.length - 1; index++) {
            try {
                String value = URLDecoder.decode(rawSegments[index].replace("+", "%2B"), StandardCharsets.UTF_8);
                segments.add(pathSegment(value));
            } catch (IllegalArgumentException exception) {
                throw new ProviderException(400, "The document path is not valid UTF-8 percent-encoding");
            }
        }
        return new Route(operation, rawPath, List.copyOf(segments), String.join("/", segments));
    }

    private Path requireDirectory(List<String> segments) throws IOException {
        Path directory = segments.isEmpty() ? config.storageRoot().toRealPath() : safeExistingPath(segments);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProviderException(409, "The parent path is not a directory");
        }
        return directory;
    }

    private Path safeExistingPath(List<String> segments) throws IOException {
        return safePath(segments, false);
    }

    private Path safePath(List<String> segments, boolean allowMissingFinal) throws IOException {
        Path root = config.storageRoot().toRealPath();
        Path current = root;
        for (int index = 0; index < segments.size(); index++) {
            current = current.resolve(pathSegment(segments.get(index))).normalize();
            if (!current.startsWith(root)) throw new ProviderException(400, "The path escapes the storage root");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new ProviderException(403, "Symbolic links are not available through this Provider");
                }
            } else if (!(allowMissingFinal && index == segments.size() - 1)) {
                throw new NoSuchFileException(current.toString());
            }
        }
        return current;
    }

    private StagedBody stageBody(HttpExchange exchange) throws Exception {
        rejectTransferEncoding(exchange);
        long declared = contentLength(exchange, true);
        if (declared > config.maxDocumentBytes()) {
            throw new ProviderException(413, "The document exceeds the Provider size limit");
        }
        Path file = state.createStagingFile();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        try (InputStream input = exchange.getRequestBody(); OutputStream output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                written += read;
                if (written > declared || written > config.maxDocumentBytes()) {
                    throw new ProviderException(400, "The request body does not match Content-Length");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(file);
            throw exception;
        }
        if (written != declared) {
            Files.deleteIfExists(file);
            throw new ProviderException(400, "The request body does not match Content-Length");
        }
        return new StagedBody(written, HexFormat.of().formatHex(digest.digest()), file);
    }

    private Body readBody(HttpExchange exchange, int maximum) throws Exception {
        rejectTransferEncoding(exchange);
        long declared = contentLength(exchange, true);
        if (declared > maximum) throw new ProviderException(413, "The request body is too large");
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length != declared) {
            throw new ProviderException(400, "The request body does not match Content-Length");
        }
        return new Body(body.length, sha256(body), body);
    }

    private static String singleString(byte[] body, String field) throws IOException {
        JsonNode node = JSON.readTree(body);
        if (node == null || !node.isObject() || node.size() != 1
                || !node.has(field) || !node.get(field).isTextual() || node.get(field).textValue().isEmpty()) {
            throw new ProviderException(400,
                    "The request body must contain only a non-empty " + field + " string");
        }
        return node.get(field).textValue();
    }

    private static String childName(String value) {
        if (value.length() > 255) throw new ProviderException(400, "The name is too long");
        return pathSegment(value);
    }

    private static String pathSegment(String value) {
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)
                || STATE_DIRECTORY.equals(value) || value.contains("/") || value.contains("\\")
                || value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) {
            throw new ProviderException(400, "The document path contains an unsupported segment");
        }
        return value;
    }

    private static void requireContentType(HttpExchange exchange, String expected) {
        if (!expected.equals(header(exchange, "Content-Type"))) {
            throw new ProviderException(415, "This operation requires " + expected);
        }
    }

    private static long contentLength(HttpExchange exchange, boolean required) {
        String value = header(exchange, "Content-Length");
        if (value == null) {
            if (required) throw new ProviderException(411, "Content-Length is required");
            return 0;
        }
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException exception) {
            throw new ProviderException(400, "Content-Length must be a non-negative integer");
        }
    }

    private static void rejectTransferEncoding(HttpExchange exchange) {
        if (header(exchange, "Transfer-Encoding") != null) {
            throw new ProviderException(400, "Chunked request bodies are not supported");
        }
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
    }

    private static String revision(BasicFileAttributes attributes) {
        return attributes.lastModifiedTime().toMillis() + "-" + attributes.size();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String stateKey(String value) {
        try {
            return sha256(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Route(String operation, String rawPath, List<String> segments, String documentPath) {}

    private static class Body {
        private final long length;
        private final String sha256;
        private final byte[] bytes;

        private Body(long length, String sha256, byte[] bytes) {
            this.length = length;
            this.sha256 = sha256;
            this.bytes = bytes;
        }

        long length() { return length; }
        String sha256() { return sha256; }
        byte[] bytes() { return bytes; }
    }

    private static final class StagedBody extends Body {
        private final Path file;
        private boolean committed;

        private StagedBody(long length, String sha256, Path file) {
            super(length, sha256, null);
            this.file = file;
        }
    }

    private record StorageEntry(
            String path,
            String name,
            String type,
            long size,
            boolean readable,
            boolean writable,
            boolean locked,
            String locker,
            String createdAt,
            String modifiedAt,
            String revision
    ) {}

    private record LockRecord(String documentPath, String owner, String createdAt) {}

    private static final class ProviderStateStore {
        private final String adapter;
        private final Path replayRoot;
        private final Path lockRoot;
        private final Path stagingRoot;

        private ProviderStateStore(Path storageRoot, String adapter) throws IOException {
            this.adapter = adapter;
            Path stateRoot = storageRoot.resolve(STATE_DIRECTORY);
            this.replayRoot = Files.createDirectories(stateRoot.resolve("replay"));
            this.lockRoot = Files.createDirectories(stateRoot.resolve("locks"));
            this.stagingRoot = Files.createDirectories(stateRoot.resolve("staging"));
        }

        Path createStagingFile() throws IOException {
            return Files.createTempFile(stagingRoot, UUID.randomUUID() + "-", ".stage");
        }

        synchronized void consumeJti(String requestId, Instant expiresAt) {
            Path file = replayRoot.resolve(stateKey(adapter + "\0" + requestId));
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Files.writeString(file, expiresAt.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    return;
                } catch (FileAlreadyExistsException exception) {
                    try {
                        Instant existing = Instant.parse(Files.readString(file, StandardCharsets.UTF_8));
                        if (!existing.isAfter(Instant.now())) {
                            Files.deleteIfExists(file);
                            continue;
                        }
                    } catch (Exception ignored) {
                        // An unreadable replay record fails closed.
                    }
                    throw new TfoStorageRequestVerifier.RequestAuthenticationException();
                } catch (IOException exception) {
                    throw new TfoStorageRequestVerifier.RequestAuthenticationException();
                }
            }
            throw new TfoStorageRequestVerifier.RequestAuthenticationException();
        }

        private Path lockFile(String documentPath) {
            return lockRoot.resolve(stateKey(adapter + "\0" + documentPath));
        }

        synchronized LockRecord currentLock(String documentPath) throws IOException {
            Path file = lockFile(documentPath);
            if (!Files.exists(file)) return null;
            return JSON.readValue(file.toFile(), LockRecord.class);
        }

        synchronized void acquireLock(String documentPath, String owner) throws IOException {
            Path file = lockFile(documentPath);
            LockRecord record = new LockRecord(documentPath, owner, Instant.now().toString());
            try {
                Files.writeString(file, JSON.writeValueAsString(record), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exception) {
                LockRecord existing = currentLock(documentPath);
                if (existing == null || !existing.owner().equals(owner)) {
                    throw new ProviderException(409, "The document is locked by another owner");
                }
            }
        }

        synchronized void releaseLock(String documentPath, String owner) throws IOException {
            LockRecord existing = currentLock(documentPath);
            if (existing == null) return;
            if (!existing.owner().equals(owner)) {
                throw new ProviderException(409, "The lock belongs to another owner");
            }
            Files.deleteIfExists(lockFile(documentPath));
        }

        synchronized void requireUnlocked(String documentPath) throws IOException {
            if (currentLock(documentPath) != null) throw new ProviderException(409, "The document is locked");
        }
    }

    private static final class ProviderException extends RuntimeException {
        private final int status;

        private ProviderException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
