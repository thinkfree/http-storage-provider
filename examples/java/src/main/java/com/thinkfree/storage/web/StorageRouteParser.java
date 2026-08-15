package com.thinkfree.storage.web;

import com.thinkfree.storage.service.StorageException;
import com.thinkfree.storage.service.StoragePathPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses the raw, signed URI without allowing Spring MVC to normalize document paths. */
@Component
public class StorageRouteParser {
    private static final String PROTOCOL_PREFIX = "/tfo-storage/v1";

    public StorageRoute parse(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new StorageException(HttpStatus.BAD_REQUEST, "Query strings are not supported");
        }
        String rawPath = request.getRequestURI();
        if (!rawPath.startsWith(PROTOCOL_PREFIX + "/")) {
            throw new StorageException(HttpStatus.NOT_FOUND, "Not found");
        }
        String[] rawSegments = rawPath.substring(PROTOCOL_PREFIX.length() + 1).split("/", -1);
        for (String rawSegment : rawSegments) {
            if (rawSegment.isEmpty()) {
                throw new StorageException(HttpStatus.BAD_REQUEST,
                        "Empty path segments are not supported");
            }
        }
        StorageOperation operation = StorageOperation.fromPathToken(rawSegments[rawSegments.length - 1]);
        if (operation == null) {
            throw new StorageException(HttpStatus.NOT_FOUND, "Unknown storage operation");
        }
        if (!operation.method().matches(request.getMethod())) {
            throw new StorageException(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }

        List<String> documentPath = new ArrayList<>();
        for (int index = 0; index < rawSegments.length - 1; index++) {
            try {
                String decoded = URLDecoder.decode(
                        rawSegments[index].replace("+", "%2B"), StandardCharsets.UTF_8);
                documentPath.add(StoragePathPolicy.requireSegment(decoded));
            } catch (IllegalArgumentException exception) {
                throw new StorageException(HttpStatus.BAD_REQUEST,
                        "The document path is not valid UTF-8 percent-encoding");
            }
        }
        return new StorageRoute(operation, rawPath, List.copyOf(documentPath));
    }
}
