package com.centralArchiveSystem.Archive.project.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "projects",
    schema = "project",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_projects_project_uid_version",
        columnNames = {"project_uid", "version_no"}
    )
)
@Schema(name = "project.projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_uid", nullable = false)
    private UUID projectUid;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    @Column(name = "previous_project_id")
    private UUID previousProjectId;

    @Column(name = "is_latest_version", nullable = false)
    private Boolean isLatestVersion = true;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_type_id")
    private UUID projectTypeId;

    @Column(name = "confidentiality_level_id")
    private UUID confidentialityLevelId;

    @Column(name = "project_number", nullable = false)
    private String projectNumber;

    @Column(name = "project_code")
    private String projectCode;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "executing_entity")
    private String executingEntity;

    @Column(name = "supervisor_name")
    private String supervisorName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "table_of_contents", columnDefinition = "TEXT")
    private String tableOfContents;

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_completion_status")
    private DataCompletionStatus dataCompletionStatus = DataCompletionStatus.INCOMPLETE;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private SourceType sourceType = SourceType.MANUAL;

    @Column(name = "source_reference")
    private String sourceReference;

    /**
     * Comma separated names of the mandatory fields that had to be substituted during a migration,
     * e.g. {@code year,name}. Empty when nothing was missing. The incomplete-data report is driven
     * from this column so reviewers can see exactly what still needs a value.
     */
    @Column(name = "missing_fields", columnDefinition = "TEXT")
    private String missingFields;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ProjectStatus {
        DRAFT("مسودة"),
        IMPORTED("مستورد"),
        UNDER_REVIEW("قيد المراجعة"),
        NEEDS_CORRECTION("يحتاج تصحيحاً"),
        REJECTED("مرفوض"),
        APPROVED("معتمد"),
        ARCHIVED("مؤرشف");

        private final String label;

        ProjectStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * The status as it reads inside a sentence, keeping the stored name beside the Arabic.
         *
         * <p>Both, because they answer different questions. Someone looking at the row sees the
         * English name in the database and the Arabic in the message, and having only the Arabic
         * would send them looking for a status that appears nowhere in the system; having only the
         * English is what this replaces, since a rejection that quotes {@code UNDER_REVIEW} to an
         * Arabic reader says nothing they can act on.
         */
        public String describe() {
            return label + " (" + name() + ")";
        }
    }

    public enum DataCompletionStatus {
        COMPLETE, INCOMPLETE
    }

    public enum SourceType {
        MANUAL, BRANCH_PACKAGE, MIGRATION
    }

    public Project() {}

    public Project(UUID id, UUID projectUid, Integer versionNo, UUID previousProjectId, Boolean isLatestVersion, UUID organizationId, UUID projectTypeId, UUID confidentialityLevelId, String projectNumber, String projectCode, String name, Integer year, String executingEntity, String supervisorName, String description, String notes, String tableOfContents, String keywords, ProjectStatus status, DataCompletionStatus dataCompletionStatus, String reviewNotes, SourceType sourceType, String sourceReference, UUID createdBy, UUID approvedBy, LocalDateTime approvedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectUid = projectUid;
        this.versionNo = versionNo;
        this.previousProjectId = previousProjectId;
        this.isLatestVersion = isLatestVersion;
        this.organizationId = organizationId;
        this.projectTypeId = projectTypeId;
        this.confidentialityLevelId = confidentialityLevelId;
        this.projectNumber = projectNumber;
        this.projectCode = projectCode;
        this.name = name;
        this.year = year;
        this.executingEntity = executingEntity;
        this.supervisorName = supervisorName;
        this.description = description;
        this.notes = notes;
        this.tableOfContents = tableOfContents;
        this.keywords = keywords;
        this.status = status;
        this.dataCompletionStatus = dataCompletionStatus;
        this.reviewNotes = reviewNotes;
        this.sourceType = sourceType;
        this.sourceReference = sourceReference;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getProjectUid() { return projectUid; }
    public void setProjectUid(UUID projectUid) { this.projectUid = projectUid; }

    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }

    public UUID getPreviousProjectId() { return previousProjectId; }
    public void setPreviousProjectId(UUID previousProjectId) { this.previousProjectId = previousProjectId; }

    public Boolean getIsLatestVersion() { return isLatestVersion; }
    public void setIsLatestVersion(Boolean isLatestVersion) { this.isLatestVersion = isLatestVersion; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public UUID getProjectTypeId() { return projectTypeId; }
    public void setProjectTypeId(UUID projectTypeId) { this.projectTypeId = projectTypeId; }

    public UUID getConfidentialityLevelId() { return confidentialityLevelId; }
    public void setConfidentialityLevelId(UUID confidentialityLevelId) { this.confidentialityLevelId = confidentialityLevelId; }

    public String getProjectNumber() { return projectNumber; }
    public void setProjectNumber(String projectNumber) { this.projectNumber = projectNumber; }

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getExecutingEntity() { return executingEntity; }
    public void setExecutingEntity(String executingEntity) { this.executingEntity = executingEntity; }

    public String getSupervisorName() { return supervisorName; }
    public void setSupervisorName(String supervisorName) { this.supervisorName = supervisorName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getTableOfContents() { return tableOfContents; }
    public void setTableOfContents(String tableOfContents) { this.tableOfContents = tableOfContents; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public DataCompletionStatus getDataCompletionStatus() { return dataCompletionStatus; }
    public void setDataCompletionStatus(DataCompletionStatus dataCompletionStatus) { this.dataCompletionStatus = dataCompletionStatus; }

    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }

    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String missingFields) { this.missingFields = missingFields; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
