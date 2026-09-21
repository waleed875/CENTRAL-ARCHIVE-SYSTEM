package com.centralArchiveSystem.Archive.audit;

import com.centralArchiveSystem.Archive.audit.dto.AuditEntityHistoryResponse;
import com.centralArchiveSystem.Archive.audit.dto.AuditLogResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public AuditLogResponse log(UUID userId, String action, String entityType,
                                 UUID entityId, String description,
                                 String oldValues, String newValues,
                                 String ipAddress) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setDescription(description);
        auditLog.setOldValues(oldValues);
        auditLog.setNewValues(newValues);
        auditLog.setIpAddress(ipAddress);

        AuditLog saved = auditLogRepository.save(auditLog);
        logger.info("Audit log created: action={}, entityType={}, entityId={}", action, entityType, entityId);
        return AuditLogResponse.fromEntity(saved);
    }

    public AuditLogResponse log(UUID userId, String user, String action, String entityType,
                                 String entityId, String oldValues, String newValues,
                                 long executionTime, String status, String errorMessage) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setEntityType(entityType);
        auditLog.setDescription(String.format("User: %s | Status: %s | Duration: %dms%s",
                user != null ? user : "anonymous",
                status,
                executionTime,
                errorMessage != null ? " | Error: " + errorMessage : ""));

        if (entityId != null) {
            try {
                auditLog.setEntityId(UUID.fromString(entityId));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid entityId format: {}", entityId);
            }
        }

        auditLog.setOldValues(oldValues);
        auditLog.setNewValues(newValues);

        AuditLog saved = auditLogRepository.save(auditLog);
        logger.info("Audit log created: userId={}, action={}, entityType={}, entityId={}",
                userId, action, entityType, entityId);
        return AuditLogResponse.fromEntity(saved);
    }

    public AuditLogResponse log(String user, String action, String entityType,
                                 String entityId, String oldValues, String newValues,
                                 long executionTime, String status, String errorMessage) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(action);
        auditLog.setEntityType(entityType);
        auditLog.setDescription(String.format("User: %s | Status: %s | Duration: %dms%s",
                user, status, executionTime,
                errorMessage != null ? " | Error: " + errorMessage : ""));

        if (entityId != null) {
            try {
                auditLog.setEntityId(UUID.fromString(entityId));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid entityId format: {}", entityId);
            }
        }

        auditLog.setOldValues(oldValues);
        auditLog.setNewValues(newValues);

        AuditLog saved = auditLogRepository.save(auditLog);
        logger.info("Audit log created: action={}, entityType={}, entityId={}", action, entityType, entityId);
        return AuditLogResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(UUID userId, String action, String entityType,
                                          UUID entityId, LocalDateTime startDate,
                                          LocalDateTime endDate, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return auditLogRepository.findFiltered(userId, action, entityType, entityId, startDate, endDate, pageable)
                .map(AuditLogResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID id) {
        AuditLog auditLog = auditLogRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Audit log not found with id: " + id));
        return AuditLogResponse.fromEntity(auditLog);
    }

    @Transactional(readOnly = true)
    public AuditEntityHistoryResponse getEntityHistory(String entityType, UUID entityId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable);

        java.util.List<AuditLogResponse> entries = auditLogs.getContent().stream()
                .map(AuditLogResponse::fromEntity)
                .collect(Collectors.toList());

        return new AuditEntityHistoryResponse(
                entityType,
                entityId.toString(),
                auditLogs.getTotalElements(),
                entries
        );
    }
}
