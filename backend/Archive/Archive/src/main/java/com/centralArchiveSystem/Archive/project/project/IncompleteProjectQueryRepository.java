package com.centralArchiveSystem.Archive.project.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface IncompleteProjectQueryRepository extends Repository<Project, UUID> {
    @Query("""
        SELECT p FROM Project p
        WHERE p.dataCompletionStatus = com.centralArchiveSystem.Archive.project.project.Project.DataCompletionStatus.INCOMPLETE
          AND p.isLatestVersion = true
          AND (p.confidentialityLevelId IS NULL OR
              (:maxLevelOrder IS NOT NULL AND EXISTS (
                  SELECT c.id FROM ConfidentialityLevel c
                  WHERE c.id = p.confidentialityLevelId AND c.levelOrder <= :maxLevelOrder)))
        ORDER BY p.createdAt DESC
        """)
    List<Project> findAccessibleIncompleteLatest(@Param("maxLevelOrder") Integer maxLevelOrder);
}
