package com.centralArchiveSystem.Archive.core.role;

import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.core.role.dto.RoleRequest;
import com.centralArchiveSystem.Archive.core.role.dto.RoleUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoleServiceTest {

    @Test
    void rejectsUndocumentedRoleCodes() {
        RoleRepository roles = mock(RoleRepository.class);
        UserRepository users = mock(UserRepository.class);
        RoleService service = new RoleService(roles, users);

        RoleRequest request = new RoleRequest(
                "custom_super_role",
                "Custom",
                "Not part of MVP"
        );

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(roles, never()).save(any());
    }

    @Test
    void deleteSoftDeactivatesWhenNoActiveUsers() {
        RoleRepository roles = mock(RoleRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        Role role = new Role();
        role.setId(id);
        role.setCode("auditor");
        role.setName("Auditor");
        role.setIsActive(true);

        when(roles.findById(id)).thenReturn(Optional.of(role));
        when(users.countActiveUsersByRoleId(id)).thenReturn(0L);

        RoleService service = new RoleService(roles, users);
        service.delete(id);

        assertFalse(role.getIsActive());
        verify(roles).save(role);
        verify(roles, never()).delete(any());
    }

    @Test
    void deleteRejectsRoleAssignedToActiveUsers() {
        RoleRepository roles = mock(RoleRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        Role role = new Role();
        role.setId(id);
        role.setCode("auditor");
        role.setName("Auditor");
        role.setIsActive(true);

        when(roles.findById(id)).thenReturn(Optional.of(role));
        when(users.countActiveUsersByRoleId(id)).thenReturn(2L);

        RoleService service = new RoleService(roles, users);

        assertThrows(BusinessRuleException.class, () -> service.delete(id));
        verify(roles, never()).save(any());
    }

    @Test
    void patchDoesNotChangeRoleCode() {
        RoleRepository roles = mock(RoleRepository.class);
        UserRepository users = mock(UserRepository.class);

        UUID id = UUID.randomUUID();
        Role role = new Role();
        role.setId(id);
        role.setCode("read_only");
        role.setName("Read Only");
        role.setIsActive(true);

        when(roles.findById(id)).thenReturn(Optional.of(role));
        when(roles.save(role)).thenReturn(role);
        when(users.countActiveUsersByRoleId(id)).thenReturn(0L);

        RoleService service = new RoleService(roles, users);
        var response = service.update(
                id,
                new RoleUpdateRequest("Viewer", "Updated", null)
        );

        assertEquals("read_only", response.code());
        assertEquals("Viewer", response.name());
    }
}
