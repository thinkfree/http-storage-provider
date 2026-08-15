package com.thinkfree.storage.service;

import org.springframework.http.HttpStatus;

/** Shared validation for decoded document path segments and child names. */
public final class StoragePathPolicy {
    private StoragePathPolicy() {}

    public static String requireSegment(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)
                || StorageStateStore.STATE_DIRECTORY.equals(value)
                || value.contains("/") || value.contains("\\")
                || value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) {
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "The document path contains an unsupported segment");
        }
        return value;
    }

    public static String requireChildName(String value) {
        if (value == null || value.length() > 255) {
            throw new StorageException(HttpStatus.BAD_REQUEST, "The name is too long");
        }
        return requireSegment(value);
    }
}
