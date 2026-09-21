package com.centralArchiveSystem.Archive.core.user;

import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.dto.UserRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserUpdateRequest;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

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

        when(users.findById(id)).thenReturn(Optional.of(user));
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

        when(users.findById(id)).thenReturn(Optional.of(user));
        when(users.existsByEmailAndIdNot("duplicate@example.com", id)).thenReturn(true);

        UserService service = new UserService(users, roles, imports, encoder);

        assertThrows(ConflictException.class,
                () -> service.update(
                        id,
                        new UserUpdateRequest(null, "duplicate@example.com", null, null, null)
                ));
        verify(users, never()).save(any());
    }
}
