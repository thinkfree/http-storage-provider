package com.thinkfree.storage.model;

import java.util.List;

/** JSON envelope returned by a list operation. */
public record StorageListResponse(List<StorageEntry> entries) {}
