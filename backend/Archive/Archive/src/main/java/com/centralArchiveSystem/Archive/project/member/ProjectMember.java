package com.centralArchiveSystem.Archive.project.member;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_members", schema = "project")
public class ProjectMember {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "person_name", nullable = false, length = 200)
    private String personName;

    @Column(name = "organization_name", length = 200)
    private String organizationName;

    @Column(name = "role_name", length = 100)
    private String roleName;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ProjectMember() {}

    public ProjectMember(UUID id, UUID projectId, String personName, String organizationName, String roleName, String notes, LocalDateTime createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.personName = personName;
        this.organizationName = organizationName;
        this.roleName = roleName;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getPersonName() { return personName; }
    public void setPersonName(String personName) { this.personName = personName; }

    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
