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

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    List<User> findByOrganizationId(UUID organizationId);

    List<User> findByStatus(User.UserStatus status);

    @Query("""
           SELECT COUNT(u) FROM User u
           JOIN u.roles r
           WHERE r.id = :roleId
             AND u.status = com.centralArchiveSystem.Archive.core.user.User.UserStatus.ACTIVE
           """)
    long countActiveUsersByRoleId(@Param("roleId") UUID roleId);

    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<User> searchByUsernameFullNameEmail(@Param("search") String search, Pageable pageable);
}
