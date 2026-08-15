package com.thinkfree.storage;

import com.thinkfree.storage.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Entry point for the Spring Boot HTTP Storage Provider example. */
@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class HttpStorageProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(HttpStorageProviderApplication.class, args);
    }
}
