package com.centralArchiveSystem.Archive.project.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

/** A database-paged review queue, not an in-memory sort of an arbitrary first page. */
public interface ReviewQueueRepository extends Repository<Project, UUID> {
    @Query("""
            SELECT p FROM Project p
            WHERE p.status = com.centralArchiveSystem.Archive.project.project.Project.ProjectStatus.UNDER_REVIEW
              AND p.isLatestVersion = true
              AND p.discardedAt IS NULL
              AND (:organizationId IS NULL OR p.organizationId = :organizationId)
              AND (:submittedBy IS NULL OR p.submittedBy = :submittedBy)
              AND (p.confidentialityLevelId IS NULL OR
                  (:maxLevelOrder IS NOT NULL AND EXISTS (
                      SELECT c.id FROM ConfidentialityLevel c
                      WHERE c.id = p.confidentialityLevelId AND c.levelOrder <= :maxLevelOrder)))
            ORDER BY CASE WHEN p.submittedAt IS NULL THEN 1 ELSE 0 END,
                     p.submittedAt ASC, p.id ASC
            """)
    Page<Project> findWaiting(@Param("organizationId") UUID organizationId,
                              @Param("submittedBy") UUID submittedBy,
                              @Param("maxLevelOrder") Integer maxLevelOrder,
                              Pageable pageable);
}
