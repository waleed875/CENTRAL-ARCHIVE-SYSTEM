package com.centralArchiveSystem.Archive.core.organization;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "organizations", schema = "core")
public class Organization {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private OrganizationType type;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", insertable = false, updatable = false)
    private Organization parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Organization> children = new ArrayList<>();

    public enum OrganizationType {
        COMPANY, FACTORY, LINE, DEPARTMENT
    }

    public Organization() {}

    public Organization(UUID id, UUID parentId, String name, String code, OrganizationType type, String description, Boolean isActive, LocalDateTime createdAt, Organization parent, List<Organization> children) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.code = code;
        this.type = type;
        this.description = description;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.parent = parent;
        this.children = children;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public OrganizationType getType() { return type; }
    public void setType(OrganizationType type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Organization getParent() { return parent; }
    public void setParent(Organization parent) { this.parent = parent; }

    public List<Organization> getChildren() { return children; }
    public void setChildren(List<Organization> children) { this.children = children; }
}
