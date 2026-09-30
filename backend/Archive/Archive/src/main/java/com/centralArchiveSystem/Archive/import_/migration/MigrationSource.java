package com.centralArchiveSystem.Archive.import_.migration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "migration_sources", schema = "import")
@Schema(name = "import.migration_sources")
public class MigrationSource {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MigrationSourceType sourceType;

    @Column(name = "database_name")
    private String databaseName;

    @Column(name = "office_name", nullable = false)
    private String officeName;

    @Column(name = "host")
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "username")
    private String username;

    @Column(name = "password_encrypted")
    private String passwordEncrypted;

    /**
     * Write-only plaintext password accepted by the settings endpoints. It is encrypted into
     * {@link #passwordEncrypted} on the way in and never populated when reading, so a response
     * can never echo the stored secret back to a client.
     */
    @Transient
    private String password;

    @Column(columnDefinition = "text")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MigrationSource() {}

    public MigrationSource(UUID id, String name, MigrationSourceType sourceType, String databaseName, String officeName, String description, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.sourceType = sourceType;
        this.databaseName = databaseName;
        this.officeName = officeName;
        this.description = description;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSourceType() { return sourceType == null ? null : sourceType.name(); }
    public void setSourceType(MigrationSourceType sourceType) { this.sourceType = sourceType; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getOfficeName() { return officeName; }
    public void setOfficeName(String officeName) { this.officeName = officeName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordEncrypted() { return passwordEncrypted; }
    public void setPasswordEncrypted(String passwordEncrypted) { this.passwordEncrypted = passwordEncrypted; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    /** True when this source carries its own connection instead of deferring to the environment. */
    @Transient
    public boolean hasStoredConnection() {
        return host != null && !host.isBlank()
                && databaseName != null && !databaseName.isBlank()
                && passwordEncrypted != null && !passwordEncrypted.isBlank();
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
