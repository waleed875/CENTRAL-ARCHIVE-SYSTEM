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

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:userId IS NULL OR a.userId = :userId) " +
           "AND (:action IS NULL OR a.action = :action) " +
           "AND (:entityType IS NULL OR a.entityType = :entityType) " +
           "AND (:entityId IS NULL OR a.entityId = :entityId) " +
           "AND (:start IS NULL OR a.createdAt >= :start) " +
           "AND (:end IS NULL OR a.createdAt <= :end)")
    Page<AuditLog> findFiltered(
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);
}
