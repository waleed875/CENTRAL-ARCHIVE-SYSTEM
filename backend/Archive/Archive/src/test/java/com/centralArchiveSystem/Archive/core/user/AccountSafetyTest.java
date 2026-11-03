package com.centralArchiveSystem.Archive.core.user;

import com.centralArchiveSystem.Archive.core.role.*;
import com.centralArchiveSystem.Archive.core.user.dto.*;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.import_.operation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSafetyTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final ImportOperationRepository imports = mock(ImportOperationRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final UserService service = new UserService(users, roles, imports, passwords);

    private User administrator() {
        Role role = new Role(); role.setId(UUID.randomUUID()); role.setCode("system_admin"); role.setIsActive(true);
        User user = new User(); user.setId(UUID.randomUUID()); user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(new HashSet<>(Set.of(role)));
        when(users.findForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(users.countActiveUsersByRoleId(role.getId())).thenReturn(1L);
        return user;
    }

    @Test void lastAdministratorCannotBeDeletedDisabledOrLoseItsRole() {
        User user = administrator(); UUID role = user.getRoles().iterator().next().getId();
        assertThrows(BusinessRuleException.class, () -> service.delete(user.getId()));
        assertThrows(BusinessRuleException.class, () -> service.update(user.getId(),
                new UserUpdateRequest(null,null,null,null,User.UserStatus.DISABLED)));
        assertThrows(BusinessRuleException.class, () -> service.removeRole(user.getId(), role));
        assertEquals(User.UserStatus.ACTIVE, user.getStatus());
        verify(users, never()).save(any());
    }

    @Test void administrativeMutationsLockBeforeLoadingAndCountingTheTarget() {
        User user = administrator();
        when(users.countActiveUsersByRoleId(user.getRoles().iterator().next().getId())).thenReturn(2L);
        service.delete(user.getId());
        var order = inOrder(users);
        order.verify(users).lockAdministration();
        order.verify(users).findForUpdate(user.getId());
        order.verify(users).countActiveUsersByRoleId(any());
        assertEquals(User.UserStatus.DISABLED, user.getStatus());
        assertNotNull(user.getTokenValidAfter());
    }

    @Test void patchCannotBypassTheActiveImportDisableGuard() {
        User user = new User(); user.setId(UUID.randomUUID());
        when(users.findForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(imports.countByImportedByAndStatus(user.getId(), ImportOperation.Status.STARTED)).thenReturn(1L);
        assertThrows(BusinessRuleException.class, () -> service.update(user.getId(),
                new UserUpdateRequest(null,null,null,null,User.UserStatus.LOCKED)));
        verify(users, never()).save(any());
    }

    @Test void resetHashesTheNewPasswordAndRevokesAllOldTokens() {
        User user = new User(); user.setId(UUID.randomUUID());
        when(users.findForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwords.encode("Reset-password-123!")).thenReturn("new-hash");
        service.resetPassword(user.getId(), new ResetPasswordRequest("Reset-password-123!"));
        assertEquals("new-hash", user.getPasswordHash());
        assertNotNull(user.getTokenValidAfter());
        verify(users).save(user);
    }
}
