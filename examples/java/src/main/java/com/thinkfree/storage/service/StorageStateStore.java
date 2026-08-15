package com.thinkfree.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkfree.storage.config.StorageProperties;
import com.thinkfree.storage.security.RequestAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Local replay, lock, and upload-staging state used by this runnable example. */
@Component
public class StorageStateStore {
    public static final String STATE_DIRECTORY = ".tfo-http-storage-state";

    private final String adapter;
    private final ObjectMapper objectMapper;
    private final Path replayDirectory;
    private final Path lockDirectory;
    private final Path stagingDirectory;

    public StorageStateStore(StorageProperties properties, ObjectMapper objectMapper) throws IOException {
        this.adapter = properties.adapter();
        this.objectMapper = objectMapper;
        Path stateDirectory = Files.createDirectories(properties.root().resolve(STATE_DIRECTORY));
        replayDirectory = Files.createDirectories(stateDirectory.resolve("replay"));
        lockDirectory = Files.createDirectories(stateDirectory.resolve("locks"));
        stagingDirectory = Files.createDirectories(stateDirectory.resolve("staging"));
    }

    public Path createStagingFile() throws IOException {
        return Files.createTempFile(stagingDirectory, UUID.randomUUID() + "-", ".stage");
    }

    public synchronized void consumeRequestId(String requestId, Instant expiresAt) {
        Path file = replayDirectory.resolve(key(adapter + "\0" + requestId));
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
                throw new RequestAuthenticationException();
            } catch (IOException exception) {
                throw new RequestAuthenticationException();
            }
        }
        throw new RequestAuthenticationException();
    }

    public synchronized LockStatus currentLock(String documentPath) throws IOException {
        Path file = lockFile(documentPath);
        if (!Files.exists(file)) return null;
        return objectMapper.readValue(file.toFile(), LockStatus.class);
    }

    public synchronized void lock(String documentPath, String owner) throws IOException {
        Path file = lockFile(documentPath);
        LockStatus lock = new LockStatus(documentPath, owner, Instant.now().toString());
        try {
            Files.writeString(file, objectMapper.writeValueAsString(lock), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException exception) {
            LockStatus existing = currentLock(documentPath);
            if (existing == null || !existing.owner().equals(owner)) {
                throw new StorageException(HttpStatus.CONFLICT, "The document is locked by another owner");
            }
        }
    }

    public synchronized void unlock(String documentPath, String owner) throws IOException {
        LockStatus existing = currentLock(documentPath);
        if (existing == null) return;
        if (!existing.owner().equals(owner)) {
            throw new StorageException(HttpStatus.CONFLICT, "The lock belongs to another owner");
        }
        Files.deleteIfExists(lockFile(documentPath));
    }

    public synchronized void requireUnlocked(String documentPath) throws IOException {
        if (currentLock(documentPath) != null) {
            throw new StorageException(HttpStatus.CONFLICT, "The document is locked");
        }
    }

    private Path lockFile(String documentPath) {
        return lockDirectory.resolve(key(adapter + "\0" + documentPath));
    }

    private static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record LockStatus(String documentPath, String owner, String createdAt) {}
}
