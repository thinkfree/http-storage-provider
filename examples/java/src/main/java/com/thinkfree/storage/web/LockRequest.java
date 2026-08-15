package com.thinkfree.storage.web;

/** Exact JSON body accepted by lock and unlock. */
public record LockRequest(String owner) {}
