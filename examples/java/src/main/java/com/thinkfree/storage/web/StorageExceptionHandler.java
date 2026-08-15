package com.thinkfree.storage.web;

import com.thinkfree.storage.security.RequestAuthenticationException;
import com.thinkfree.storage.service.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;

/** Central Spring MVC error mapping that does not disclose authentication details. */
@RestControllerAdvice
public class StorageExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageExceptionHandler.class);

    @ExceptionHandler(RequestAuthenticationException.class)
    ResponseEntity<String> authenticationFailure() {
        return response(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<String> storageFailure(StorageException exception) {
        return response(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(NoSuchFileException.class)
    ResponseEntity<String> notFound() {
        return response(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(DirectoryNotEmptyException.class)
    ResponseEntity<String> directoryNotEmpty() {
        return response(HttpStatus.CONFLICT, "The directory is not empty");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<String> accessDenied() {
        return response(HttpStatus.FORBIDDEN, "Storage access was denied");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpectedFailure(Exception exception) {
        LOGGER.error("Storage request failed: {}", exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Storage request failed");
    }

    private static ResponseEntity<String> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN)
                .body(message);
    }
}
