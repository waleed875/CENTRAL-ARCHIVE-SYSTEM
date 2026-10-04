package com.centralArchiveSystem.Archive.import_.migration.dto;

import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record MigrationSourceUpdateRequest(
    @Size(max = 255) String name,
    MigrationSourceType sourceType,
    @Size(max = 255) String databaseName,
    @Size(max = 255) String officeName,
    @Size(max = 200) String host,
    @Min(value = 1, message = "يجب أن يكون المنفذ بين 1 و 65535")
    @Max(value = 65535, message = "يجب أن يكون المنفذ بين 1 و 65535") Integer port,
    @Size(max = 200) String username,
    // Blank leaves the stored password untouched, so an editor can change the host without
    // having to retype the secret.
    @Size(max = 200) String password,
    String description
) {}
