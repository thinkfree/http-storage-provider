package com.thinkfree.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Spring Boot external configuration for the example Provider.
 *
 * <p>{@code application.properties} maps the documented environment variables
 * to these properties, so the same values work locally, in containers, and in
 * a Spring configuration file.</p>
 */
@ConfigurationProperties(prefix = "tfo.storage")
public class ProviderConfig {
    private static final Pattern ADAPTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private Path root = Path.of("./storage");
    private String rootName = "Documents";
    private String adapter;
    private String requestJwtSecret;
    private long maxDocumentBytes = 536_870_912L;

    @PostConstruct
    void validate() {
        root = root.toAbsolutePath().normalize();
        if (adapter == null || !ADAPTER.matcher(adapter).matches()) {
            throw new IllegalArgumentException(
                    "TFO_STORAGE_ADAPTER is required and contains unsupported characters");
        }
        if (requestJwtSecret == null
                || requestJwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "TFO_STORAGE_REQUEST_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (maxDocumentBytes < 1) {
            throw new IllegalArgumentException("TFO_STORAGE_MAX_DOCUMENT_BYTES must be positive");
        }
    }

    public Path storageRoot() { return root; }
    public String rootName() { return rootName; }
    public String adapter() { return adapter; }
    public String requestJwtSecret() { return requestJwtSecret; }
    public long maxDocumentBytes() { return maxDocumentBytes; }

    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public String getRootName() { return rootName; }
    public void setRootName(String rootName) { this.rootName = rootName; }
    public String getAdapter() { return adapter; }
    public void setAdapter(String adapter) { this.adapter = adapter; }
    public String getRequestJwtSecret() { return requestJwtSecret; }
    public void setRequestJwtSecret(String requestJwtSecret) { this.requestJwtSecret = requestJwtSecret; }
    public long getMaxDocumentBytes() { return maxDocumentBytes; }
    public void setMaxDocumentBytes(long maxDocumentBytes) { this.maxDocumentBytes = maxDocumentBytes; }
}
