package com.thinkfree.storage.security;

/** Deliberately hides the reason a signed request was rejected. */
public class RequestAuthenticationException extends SecurityException {
    public RequestAuthenticationException() {
        super("Unauthorized");
    }
}
