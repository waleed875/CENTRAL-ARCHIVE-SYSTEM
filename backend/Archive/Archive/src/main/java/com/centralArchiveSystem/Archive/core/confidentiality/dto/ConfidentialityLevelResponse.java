package com.centralArchiveSystem.Archive.core.confidentiality.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response body for a confidentiality level")
public record ConfidentialityLevelResponse(
    @Schema(description = "Unique identifier of the confidentiality level") UUID id,
    @Schema(description = "Unique code for the confidentiality level", example = "TOP_SECRET") String code,
    @Schema(description = "Display name of the confidentiality level", example = "Top Secret") String name,
    @Schema(description = "Numeric ordering of the level", example = "1") Integer levelOrder,
    @Schema(description = "Description of the confidentiality level", example = "Highest level of confidentiality") String description,
    @Schema(description = "Timestamp when the level was created") LocalDateTime createdAt,
    @Schema(description = "Number of projects using this confidentiality level", example = "5") Long projectsCount,
    @Schema(description = "Number of files with this confidentiality level", example = "120") Long filesCount,
    @Schema(description = "Number of users assigned this confidentiality level", example = "30") Long usersCount
) {}
