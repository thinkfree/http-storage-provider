package com.thinkfree.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

public record ProviderConfig(
        String host,
        int port,
        Path storageRoot,
        String rootName,
        String adapter,
        String requestJwtSecret,
        long maxDocumentBytes
) {
    private static final Pattern ADAPTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ProviderConfig {
        storageRoot = storageRoot.toAbsolutePath().normalize();
        if (!ADAPTER.matcher(adapter).matches()) {
            throw new IllegalArgumentException("adapter contains unsupported characters");
        }
        if (requestJwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("requestJwtSecret must contain at least 32 UTF-8 bytes");
        }
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port is invalid");
        if (maxDocumentBytes < 1) throw new IllegalArgumentException("maxDocumentBytes must be positive");
    }

    public static ProviderConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ProviderConfig fromEnvironment(Map<String, String> environment) {
        return new ProviderConfig(
                environment.getOrDefault("TFO_STORAGE_HOST", "127.0.0.1"),
                parseInteger(environment.getOrDefault("TFO_STORAGE_PORT", "8080"), "TFO_STORAGE_PORT"),
                Path.of(environment.getOrDefault("TFO_STORAGE_ROOT", "./storage")),
                environment.getOrDefault("TFO_STORAGE_ROOT_NAME", "Documents"),
                required(environment, "TFO_STORAGE_ADAPTER"),
                required(environment, "TFO_STORAGE_REQUEST_JWT_SECRET"),
                parseLong(environment.getOrDefault("TFO_STORAGE_MAX_DOCUMENT_BYTES", "536870912"),
                        "TFO_STORAGE_MAX_DOCUMENT_BYTES")
        );
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
