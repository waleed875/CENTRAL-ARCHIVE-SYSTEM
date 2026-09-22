package com.centralArchiveSystem.Archive.core.role;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    List<Role> findByIsActive(Boolean isActive);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("""
           SELECT r FROM Role r
           WHERE (:search IS NULL OR :search = ''
                  OR LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(r.code) LIKE LOWER(CONCAT('%', :search, '%')))
             AND (:isActive IS NULL OR r.isActive = :isActive)
           """)
    Page<Role> searchFiltered(
            @Param("search") String search,
            @Param("isActive") Boolean isActive,
            Pageable pageable);
}
