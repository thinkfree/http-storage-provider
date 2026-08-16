package com.thinkfree.storage.service;

import com.thinkfree.storage.config.StorageProperties;
import com.thinkfree.storage.model.StorageEntry;
import com.thinkfree.storage.model.StorageListResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local-filesystem storage implementation used to keep the Spring example runnable.
 * Production Providers can replace this service without changing the web or security layers.
 */
@Service
public class LocalDirectoryStorageService {
    private final StorageProperties properties;
    private final StorageStateStore stateStore;
    private final Path root;

    public LocalDirectoryStorageService(StorageProperties properties, StorageStateStore stateStore)
            throws IOException {
        this.properties = properties;
        this.stateStore = stateStore;
        Files.createDirectories(properties.root());
        root = properties.root().toRealPath();
    }

    public StorageEntry info(List<String> path) throws IOException {
        Path item = path.isEmpty() ? root : existingPath(path);
        BasicFileAttributes attributes = Files.readAttributes(
                item, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() && !attributes.isDirectory()) {
            throw new StorageException(HttpStatus.FORBIDDEN, "This storage item type is not supported");
        }
        if (attributes.isRegularFile() && attributes.size() > properties.maxDocumentBytes()) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The document exceeds the Provider size limit");
        }
        String documentPath = String.join("/", path);
        StorageStateStore.LockStatus lock = stateStore.currentLock(documentPath);
        return new StorageEntry(
                documentPath,
                path.isEmpty() ? properties.rootName() : path.get(path.size() - 1),
                attributes.isDirectory() ? "directory" : "file",
                attributes.isDirectory() ? 0 : attributes.size(),
                Files.isReadable(item),
                Files.isWritable(item),
                lock != null,
                lock == null ? null : lock.owner(),
                attributes.creationTime().toInstant().toString(),
                attributes.lastModifiedTime().toInstant().toString(),
                revision(attributes)
        );
    }

    public StorageListResponse list(List<String> path) throws IOException {
        Path directory = directory(path);
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                child -> !StorageStateStore.STATE_DIRECTORY.equals(child.getFileName().toString()))) {
            for (Path child : stream) children.add(child);
        }
        children.sort(Comparator.comparing(child -> child.getFileName().toString()));
        if (children.size() > 10_000) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The directory contains more than 10000 entries");
        }
        List<StorageEntry> entries = new ArrayList<>();
        for (Path child : children) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(child.getFileName().toString());
            entries.add(info(List.copyOf(childPath)));
        }
        return new StorageListResponse(List.copyOf(entries));
    }

    public Download download(List<String> path) throws IOException {
        Path file = existingPath(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(HttpStatus.CONFLICT, "The requested item is not a file");
        }
        if (Files.size(file) > properties.maxDocumentBytes()) {
            throw new StorageException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The document exceeds the Provider size limit");
        }
        return new Download(new FileSystemResource(file), Files.size(file));
    }

    public String save(List<String> path, Path stagedFile) throws IOException {
        if (path.isEmpty()) {
            throw new StorageException(HttpStatus.BAD_REQUEST, "A document path is required");
        }
        directory(path.subList(0, path.size() - 1));
        Path target = path(path, true);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(HttpStatus.CONFLICT, "A directory already uses this path");
        }
        try {
            Files.move(stagedFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(stagedFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return revision(Files.readAttributes(target, BasicFileAttributes.class));
    }

    public void lock(List<String> path, String owner) throws IOException {
        info(path);
        stateStore.lock(String.join("/", path), owner);
    }

    public void unlock(List<String> path, String owner) throws IOException {
        stateStore.unlock(String.join("/", path), owner);
    }

    public void createDirectory(List<String> path, String name) throws IOException {
        Path target = directory(path).resolve(StoragePathPolicy.requireChildName(name));
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException exception) {
            throw new StorageException(HttpStatus.CONFLICT, "An item already uses this name");
        }
    }

    public void rename(List<String> path, String name) throws IOException {
        if (path.isEmpty()) {
            throw new StorageException(HttpStatus.BAD_REQUEST, "The storage root cannot be renamed");
        }
        String documentPath = String.join("/", path);
        stateStore.requireUnlocked(documentPath);
        Path source = existingPath(path);
        Path target = source.getParent().resolve(StoragePathPolicy.requireChildName(name));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(HttpStatus.CONFLICT, "An item already uses this name");
        }
        Files.move(source, target);
    }

    public void delete(List<String> path) throws IOException {
        if (path.isEmpty()) {
            throw new StorageException(HttpStatus.BAD_REQUEST, "The storage root cannot be deleted");
        }
        stateStore.requireUnlocked(String.join("/", path));
        Files.delete(existingPath(path));
    }

    private Path directory(List<String> path) throws IOException {
        Path directory = path.isEmpty() ? root : existingPath(path);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(HttpStatus.CONFLICT, "The parent path is not a directory");
        }
        return directory;
    }

    private Path existingPath(List<String> path) throws IOException {
        return path(path, false);
    }

    private Path path(List<String> segments, boolean allowMissingFinal) throws IOException {
        Path current = root;
        for (int index = 0; index < segments.size(); index++) {
            current = current.resolve(StoragePathPolicy.requireSegment(segments.get(index))).normalize();
            if (!current.startsWith(root)) {
                throw new StorageException(HttpStatus.BAD_REQUEST, "The path escapes the storage root");
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new StorageException(HttpStatus.FORBIDDEN,
                            "Symbolic links are not available through this Provider");
                }
            } else if (!(allowMissingFinal && index == segments.size() - 1)) {
                throw new NoSuchFileException(current.toString());
            }
        }
        return current;
    }

    private static String revision(BasicFileAttributes attributes) {
        return attributes.lastModifiedTime().toMillis() + "-" + attributes.size();
    }

    public record Download(FileSystemResource resource, long contentLength) {}
}
