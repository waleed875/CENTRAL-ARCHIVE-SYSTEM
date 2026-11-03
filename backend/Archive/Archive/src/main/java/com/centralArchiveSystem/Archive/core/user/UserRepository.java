package com.centralArchiveSystem.Archive.core.user;

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
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findForUpdate(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.username = :username")
    Optional<User> findForUpdateByUsername(@Param("username") String username);

    /** One transaction-scoped guard shared by user and role administration, across app instances. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(932486010)", nativeQuery = true)
    Integer lockAdministration();

    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    Optional<com.centralArchiveSystem.Archive.core.organization.Organization> findOrganization(@Param("id") UUID id);

    @Query("SELECT c FROM ConfidentialityLevel c WHERE c.id = :id")
    Optional<com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel> findClearance(@Param("id") UUID id);

    long countByStatus(User.UserStatus status);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    List<User> findByOrganizationId(UUID organizationId);

    List<User> findByStatus(User.UserStatus status);

    long countByMaxConfidentialityLevelId(UUID maxConfidentialityLevelId);

    @Query("""
           SELECT COUNT(u) FROM User u
           JOIN u.roles r
           WHERE r.id = :roleId
             AND u.status = com.centralArchiveSystem.Archive.core.user.User.UserStatus.ACTIVE
           """)
    long countActiveUsersByRoleId(@Param("roleId") UUID roleId);

    @Query("""
           SELECT DISTINCT u FROM User u
           LEFT JOIN u.roles r
           WHERE (:search IS NULL OR :search = ''
                  OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :search, '%')))
             AND (:organizationId IS NULL OR u.organizationId = :organizationId)
             AND (:status IS NULL OR u.status = :status)
             AND (:roleCode IS NULL OR r.code = :roleCode)
           """)
    Page<User> searchFiltered(
            @Param("search") String search,
            @Param("organizationId") UUID organizationId,
            @Param("status") User.UserStatus status,
            @Param("roleCode") String roleCode,
            Pageable pageable);
}
