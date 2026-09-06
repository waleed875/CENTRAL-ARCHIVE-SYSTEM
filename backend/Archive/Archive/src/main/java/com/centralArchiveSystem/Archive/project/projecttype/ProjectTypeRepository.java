package com.centralArchiveSystem.Archive.project.projecttype;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectTypeRepository extends JpaRepository<ProjectType, UUID> {

    List<ProjectType> findByIsActive(Boolean isActive);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
