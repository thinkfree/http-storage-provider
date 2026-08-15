package com.thinkfree.storage.service;

import org.springframework.http.HttpStatus;

/** Expected Provider failure that maps to a stable HTTP response. */
public class StorageException extends RuntimeException {
    private final HttpStatus status;

    public StorageException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
