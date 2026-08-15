package com.thinkfree.storage.web;

import org.springframework.http.HttpMethod;

import java.util.Arrays;

/** Protocol operations and their exact HTTP/body contracts. */
public enum StorageOperation {
    INFO("info", HttpMethod.GET, BodyKind.NONE),
    LIST("list", HttpMethod.GET, BodyKind.NONE),
    GET("get", HttpMethod.GET, BodyKind.NONE),
    PUT("put", HttpMethod.PUT, BodyKind.DOCUMENT),
    LOCK("lock", HttpMethod.POST, BodyKind.JSON),
    UNLOCK("unlock", HttpMethod.POST, BodyKind.JSON),
    MKDIR("mkdir", HttpMethod.POST, BodyKind.JSON),
    RENAME("rename", HttpMethod.POST, BodyKind.JSON),
    DELETE("delete", HttpMethod.DELETE, BodyKind.NONE);

    private final String pathToken;
    private final HttpMethod method;
    private final BodyKind bodyKind;

    StorageOperation(String pathToken, HttpMethod method, BodyKind bodyKind) {
        this.pathToken = pathToken;
        this.method = method;
        this.bodyKind = bodyKind;
    }

    public HttpMethod method() { return method; }
    public BodyKind bodyKind() { return bodyKind; }

    public static StorageOperation fromPathToken(String token) {
        return Arrays.stream(values())
                .filter(operation -> operation.pathToken.equals(token))
                .findFirst()
                .orElse(null);
    }

    public enum BodyKind { NONE, JSON, DOCUMENT }
}
