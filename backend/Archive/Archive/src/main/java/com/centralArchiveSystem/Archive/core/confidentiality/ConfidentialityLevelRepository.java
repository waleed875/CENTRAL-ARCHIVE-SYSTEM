package com.centralArchiveSystem.Archive.core.confidentiality;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfidentialityLevelRepository extends JpaRepository<ConfidentialityLevel, UUID> {

    Optional<ConfidentialityLevel> findByCode(String code);

    Optional<ConfidentialityLevel> findByLevelOrder(Integer levelOrder);

    List<ConfidentialityLevel> findAllByOrderByLevelOrderAsc();
}
