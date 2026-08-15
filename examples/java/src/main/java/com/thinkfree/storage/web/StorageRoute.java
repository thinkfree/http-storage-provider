package com.thinkfree.storage.web;

import java.util.List;

/** Parsed operation suffix and decoded document path. */
public record StorageRoute(StorageOperation operation, String rawPath, List<String> path) {}
