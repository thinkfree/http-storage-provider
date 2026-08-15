package com.thinkfree.storage.model;

/** JSON metadata returned by the info and list operations. */
public record StorageEntry(
        String path,
        String name,
        String type,
        long size,
        boolean readable,
        boolean writable,
        boolean locked,
        String locker,
        String createdAt,
        String modifiedAt,
        String revision
) {}
