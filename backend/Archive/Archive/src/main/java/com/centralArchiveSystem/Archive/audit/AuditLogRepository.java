package com.centralArchiveSystem.Archive.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByUserId(UUID userId, Pageable pageable);

    Page<AuditLog> findByAction(String action, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndEntityId(String entityType, UUID entityId, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * Optional filters, all of them nullable at once, which is the state the audit log screen opens
     * in.
     *
     * <p>Each parameter appears twice in the generated SQL: once on the left of {@code IS NULL} and
     * once compared to the column. The first occurrence has nothing to give PostgreSQL a type, and
     * for a {@code LocalDateTime} — which Hibernate binds without a type it can send — that is fatal:
     * {@code could not determine data type of parameter $9}, surfacing as a 500 on every call.
     * Casting inside the query makes the type part of the SQL text, so it does not depend on how the
     * driver binds a null. The other repositories use the same {@code (:p IS NULL OR ...)} shape and
     * work because none of them take a date range.
     *
     * <p>See {@code AuditLogSearchIntegrationTest}, which is the only thing that has ever run this
     * query. Before it existed the query was translated and compiled on every build and never
     * executed, so the audit log answered 500 while the whole suite reported green.
     */
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(cast(:userId as java.util.UUID) IS NULL OR a.userId = :userId) " +
           "AND (cast(:action as string) IS NULL OR a.action = :action) " +
           "AND (cast(:entityType as string) IS NULL OR a.entityType = :entityType) " +
           "AND (cast(:entityId as java.util.UUID) IS NULL OR a.entityId = :entityId) " +
           "AND (cast(:start as java.time.LocalDateTime) IS NULL OR a.createdAt >= :start) " +
           "AND (cast(:end as java.time.LocalDateTime) IS NULL OR a.createdAt <= :end)")
    Page<AuditLog> findFiltered(
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);
}
