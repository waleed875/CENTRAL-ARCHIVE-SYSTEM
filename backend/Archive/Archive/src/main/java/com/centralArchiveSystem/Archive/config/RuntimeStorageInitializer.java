package com.centralArchiveSystem.Archive.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeStorageInitializer implements ApplicationRunner {

    private final Path multipartDirectory;

    public RuntimeStorageInitializer(
            @Value("${spring.servlet.multipart.location}") String multipartLocation) {
        this.multipartDirectory = Path.of(multipartLocation).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(multipartDirectory);
        if (!Files.isDirectory(multipartDirectory) || !Files.isWritable(multipartDirectory)) {
            throw new IllegalStateException(
                    "Multipart storage directory is not writable: " + multipartDirectory);
        }
    }

    Path multipartDirectory() {
        return multipartDirectory;
    }
}
