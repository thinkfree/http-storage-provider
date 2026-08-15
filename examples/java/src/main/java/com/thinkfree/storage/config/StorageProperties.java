package com.thinkfree.storage.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Type-safe Spring configuration for the Provider. */
@Validated
@ConfigurationProperties(prefix = "tfo.storage")
public record StorageProperties(
        Path root,
        @NotBlank String rootName,
        @NotBlank String adapter,
        @NotBlank String requestJwtSecret,
        @Positive long maxDocumentBytes
) {
    private static final Pattern ADAPTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public StorageProperties {
        root = root.toAbsolutePath().normalize();
        if (!ADAPTER.matcher(adapter).matches()) {
            throw new IllegalArgumentException("TFO_STORAGE_ADAPTER contains unsupported characters");
        }
        if (requestJwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "TFO_STORAGE_REQUEST_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
    }
}
