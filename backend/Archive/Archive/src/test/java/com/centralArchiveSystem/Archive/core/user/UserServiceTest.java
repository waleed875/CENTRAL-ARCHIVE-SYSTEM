package com.centralArchiveSystem.Archive.core.user;

import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.dto.UserRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserResponse;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Test
    void removingAccountAnonymizesIdentityButKeepsAuditId() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
        UUID target = UUID.randomUUID(), administrator = UUID.randomUUID();
        User user = new User();
        user.setId(target);
        user.setUsername("oldlogin");
        user.setFullName("Historical Employee");
        user.setEmail("employee@example.invalid");
        user.setPasswordHash("oldhash");
        user.setOrganizationId(UUID.randomUUID());
        user.setMaxConfidentialityLevelId(UUID.randomUUID());
        user.setStatus(User.UserStatus.ACTIVE);
        when(users.findForUpdate(target)).thenReturn(Optional.of(user));

        new UserService(users, roles, imports, encoder)
                .removeAccount(target, administrator, "Required under administration retention policy");

        assertEquals(target, user.getId());
        assertEquals(User.UserStatus.DELETED, user.getStatus());
        assertTrue(user.getUsername().startsWith("deleted_"));
        assertEquals("حساب محذوف", user.getFullName());
        assertNull(user.getEmail());
        assertNull(user.getOrganizationId());
        assertNull(user.getMaxConfidentialityLevelId());
        org.mockito.ArgumentCaptor<String> generatedPassword = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(generatedPassword.capture());
        assertTrue(generatedPassword.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72);
        assertTrue(encoder.matches(generatedPassword.getValue(), user.getPasswordHash()));
        assertNotNull(user.getDeletedAt());
        assertEquals(administrator, user.getDeletedBy());
        assertNotNull(user.getTokenValidAfter());
        verify(users).save(user);
        verify(users).lockAdministration();
    }

    @Test
    void accountRemovalAcceptsShortNonBlankReasonAndTrimsIt() {
        UserRepository users = mock(UserRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        UUID target = UUID.randomUUID(), administrator = UUID.randomUUID();
        User user = new User();
        user.setId(target);
        user.setStatus(User.UserStatus.ACTIVE);
        when(users.findForUpdate(target)).thenReturn(Optional.of(user));
        when(imports.countByImportedByAndStatus(target, ImportOperation.Status.STARTED)).thenReturn(0L);

        UserService service = new UserService(users, mock(RoleRepository.class),
                imports, mock(PasswordEncoder.class));
        service.removeAccount(target, administrator, " ok ");

        assertEquals("ok", user.getDeletionReason());
        verify(users).save(user);
    }

    @Test
    void accountRemovalRejectsSelfAndActiveImports() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setStatus(User.UserStatus.ACTIVE);
        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        when(imports.countByImportedByAndStatus(id, ImportOperation.Status.STARTED)).thenReturn(1L);
        UserService service = new UserService(users, roles, imports, encoder);
        assertThrows(BusinessRuleException.class, () ->
                service.removeAccount(id, id, "Account requested to be deleted"));
        assertThrows(BusinessRuleException.class, () ->
                service.removeAccount(id, UUID.randomUUID(), "Account requested to be deleted"));
        verify(users, never()).save(any());
    }

    @Test
    void deletedAccountCannotBeUpdatedOrHavePasswordReset() {
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        UUID id = UUID.randomUUID();
        user.setId(id);
        user.setStatus(User.UserStatus.DELETED);
        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        UserService service = new UserService(users, mock(RoleRepository.class),
                mock(ImportOperationRepository.class), mock(PasswordEncoder.class));
        assertThrows(BusinessRuleException.class, () ->
                service.update(id, new UserUpdateRequest("useragain",null,null,null,null,null,false)));
        assertThrows(BusinessRuleException.class, () ->
                service.resetPassword(id, new com.centralArchiveSystem.Archive.core.user.dto.ResetPasswordRequest("Strong-password123!")));
        verify(users, never()).save(any());
    }

    @Test
    void deleteRejectsUserWithActiveImportOperation() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setUsername("importer");
        user.setStatus(User.UserStatus.ACTIVE);

        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        when(imports.countByImportedByAndStatus(id, ImportOperation.Status.STARTED)).thenReturn(1L);

        UserService service = new UserService(users, roles, imports, encoder);

        assertThrows(BusinessRuleException.class, () -> service.delete(id));
        verify(users, never()).save(any());
    }

    @Test
    void createRejectsMissingRoleIds() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID missingRole = UUID.randomUUID();
        when(users.existsByUsername("user1")).thenReturn(false);
        when(encoder.encode("StrongPassword123!")).thenReturn("hash");
        when(roles.findAllById(Set.of(missingRole))).thenReturn(java.util.List.of());

        UserService service = new UserService(users, roles, imports, encoder);

        UserRequest request = new UserRequest(
                "user1",
                "StrongPassword123!",
                "User One",
                null,
                null,
                null,
                Set.of(missingRole)
        );

        assertThrows(ResourceNotFoundException.class, () -> service.create(request));
        verify(users, never()).save(any());
    }

    @Test
    void createAllowsOmittingFullNameAndEmailAndUsesUsernameAsDisplayName() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        when(users.existsByUsername("archivist")).thenReturn(false);
        when(encoder.encode("StrongPassword123!")).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserService service = new UserService(users, roles, imports, encoder);
        UserRequest request = new UserRequest(
                "archivist",
                "StrongPassword123!",
                null,
                null,
                null,
                null,
                Set.of()
        );

        UserResponse created = service.create(request);

        assertEquals("archivist", created.fullName());
        assertNull(created.email());
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals("archivist", saved.getValue().getFullName());
        assertNull(saved.getValue().getEmail());
    }

    @Test
    void adminUsernameChangeRevokesExistingTokens() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID id = UUID.randomUUID();
        User user = new User(); user.setId(id); user.setUsername("oldname");
        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
        UserService service = new UserService(users, roles, imports, encoder);
        service.update(id, new UserUpdateRequest("newname", null, null, null, null, null, false));
        assertEquals("newname", user.getUsername());
        assertNotNull(user.getTokenValidAfter());
        verify(users).existsByUsernameAndIdNot("newname", id);
    }

    @Test
    void adminCanRemoveClearanceAndRevokesTokens() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UUID id = UUID.randomUUID();
        User user = new User(); user.setId(id); user.setUsername("employee");
        user.setMaxConfidentialityLevelId(UUID.randomUUID());
        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
        UserService service = new UserService(users, roles, imports, encoder);
        service.update(id, new UserUpdateRequest(null, null, null, null, null, null, true));
        assertNull(user.getMaxConfidentialityLevelId());
        assertNotNull(user.getTokenValidAfter());
    }

    @Test
    void patchRejectsDuplicateEmail() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setUsername("user1");
        user.setFullName("User One");
        user.setStatus(User.UserStatus.ACTIVE);

        when(users.findForUpdate(id)).thenReturn(Optional.of(user));
        when(users.existsByEmailAndIdNot("duplicate@example.com", id)).thenReturn(true);

        UserService service = new UserService(users, roles, imports, encoder);

        assertThrows(ConflictException.class,
                () -> service.update(
                        id,
                        new UserUpdateRequest(null, null, "duplicate@example.com", null, null, null, false)
                ));
        verify(users, never()).save(any());
    }
    @Test
    void addRoleRejectsInactiveRole() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);
        user.setUsername("user1");
        user.setStatus(User.UserStatus.ACTIVE);

        com.centralArchiveSystem.Archive.core.role.Role role =
                new com.centralArchiveSystem.Archive.core.role.Role();
        role.setId(roleId);
        role.setCode("auditor");
        role.setIsActive(false);

        when(users.findForUpdate(userId)).thenReturn(Optional.of(user));
        when(roles.findById(roleId)).thenReturn(Optional.of(role));

        UserService service = new UserService(users, roles, imports, encoder);

        assertThrows(BusinessRuleException.class, () -> service.addRole(userId, roleId));
        verify(users, never()).save(any());
    }

    @Test
    void addRoleRejectsDuplicateAssignment() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        com.centralArchiveSystem.Archive.core.role.Role role =
                new com.centralArchiveSystem.Archive.core.role.Role();
        role.setId(roleId);
        role.setCode("auditor");
        role.setIsActive(true);

        User user = new User();
        user.setId(userId);
        user.setUsername("user1");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(new java.util.HashSet<>(Set.of(role)));

        when(users.findForUpdate(userId)).thenReturn(Optional.of(user));
        when(roles.findById(roleId)).thenReturn(Optional.of(role));

        UserService service = new UserService(users, roles, imports, encoder);

        assertThrows(ConflictException.class, () -> service.addRole(userId, roleId));
        verify(users, never()).save(any());
    }

    @Test
    void administratorCanRevokeAnAssignedRoleAndExistingInactiveRolesDoNotBlockEdits() {
        UserRepository users = mock(UserRepository.class);
        Role inactiveRole = new Role();
        UUID roleId = UUID.randomUUID(), userId = UUID.randomUUID();
        inactiveRole.setId(roleId);
        inactiveRole.setCode("auditor");
        inactiveRole.setIsActive(false);
        User user = new User();
        user.setId(userId);
        user.setUsername("disabled-role-holder");
        user.setStatus(User.UserStatus.DISABLED);
        user.setRoles(new java.util.HashSet<>(Set.of(inactiveRole)));
        when(users.findForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);

        UserService service = new UserService(users, mock(RoleRepository.class),
                mock(ImportOperationRepository.class), mock(PasswordEncoder.class));
        service.update(userId, new UserUpdateRequest(null, "Display name", null, null,
                null, null, false, Set.of(roleId)));
        assertEquals(Set.of(inactiveRole), user.getRoles(), "an existing disabled role can be retained while editing other fields");

        service.update(userId, new UserUpdateRequest(null, null, null, null,
                null, null, false, Set.of()));
        assertTrue(user.getRoles().isEmpty(), "an administrator can revoke the inactive assigned role");
        assertNotNull(user.getTokenValidAfter());
        verify(users, times(2)).save(user);
    }

    @Test
    void directRoleRevocationInvalidatesSessionsAndProtectsTheLastAdministrator() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        UUID userId = UUID.randomUUID(), roleId = UUID.randomUUID();
        Role adminRole = new Role();
        adminRole.setId(roleId);
        adminRole.setCode("system_admin");
        adminRole.setIsActive(true);
        User admin = new User();
        admin.setId(userId);
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(new java.util.HashSet<>(Set.of(adminRole)));
        when(users.findForUpdate(userId)).thenReturn(Optional.of(admin));
        when(roles.findById(roleId)).thenReturn(Optional.of(adminRole));
        when(users.countActiveUsersByRoleId(roleId)).thenReturn(1L);

        UserService service = new UserService(users, roles, mock(ImportOperationRepository.class),
                mock(PasswordEncoder.class));
        assertThrows(BusinessRuleException.class, () -> service.removeRole(userId, roleId));
        verify(users, never()).save(any());

        when(users.countActiveUsersByRoleId(roleId)).thenReturn(2L);
        service.removeRole(userId, roleId);
        assertTrue(admin.getRoles().isEmpty());
        assertNotNull(admin.getTokenValidAfter());
        verify(users).save(admin);
    }

    @Test
    void disablingUserAdvancesTokenCutoff() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ImportOperationRepository imports = mock(ImportOperationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setUsername("user1");
        user.setStatus(User.UserStatus.ACTIVE);

        when(users.findForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);

        UserService service = new UserService(users, roles, imports, encoder);
        service.update(
                userId,
                new UserUpdateRequest(null, null, null, null, User.UserStatus.DISABLED)
        );

        assertEquals(User.UserStatus.DISABLED, user.getStatus());
        assertNotNull(user.getTokenValidAfter());
        verify(users).save(user);
    }

}
