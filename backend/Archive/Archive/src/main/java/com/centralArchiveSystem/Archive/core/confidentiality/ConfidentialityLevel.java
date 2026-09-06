package com.centralArchiveSystem.Archive.core.confidentiality;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "confidentiality_levels", schema = "core")
public class ConfidentialityLevel {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "level_order", nullable = false, unique = true)
    private Integer levelOrder;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ConfidentialityLevel() {}

    public ConfidentialityLevel(UUID id, String code, String name, Integer levelOrder, String description, LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.levelOrder = levelOrder;
        this.description = description;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getLevelOrder() { return levelOrder; }
    public void setLevelOrder(Integer levelOrder) { this.levelOrder = levelOrder; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
