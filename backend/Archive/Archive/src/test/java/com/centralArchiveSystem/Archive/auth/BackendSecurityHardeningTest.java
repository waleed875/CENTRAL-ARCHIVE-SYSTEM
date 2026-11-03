package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.common.validation.PasswordPolicyValidator;
import com.centralArchiveSystem.Archive.config.AsyncSecurityConfig;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendSecurityHardeningTest {
    @Test void passwordPolicyCountsArabicUtf8Bytes() {
        var validator = new PasswordPolicyValidator();
        assertFalse(validator.isValid("short", null));
        assertTrue(validator.isValid("Valid-password-123", null));
        assertTrue(validator.isValid("ع".repeat(36), null));
        assertFalse(validator.isValid("ع".repeat(37), null));
        assertFalse(validator.isValid("a".repeat(73), null));
    }

    @Test void liveChangePasswordDtoUsesTheSharedPolicy() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new com.centralArchiveSystem.Archive.auth.dto.ChangePasswordRequest("old-password", "a")).isEmpty());
            assertTrue(validator.validate(new com.centralArchiveSystem.Archive.auth.dto.ChangePasswordRequest("old-password", "Valid-password-123")).isEmpty());
        }
    }

    @Test void inactiveRolesNeverBecomeAuthorities() {
        UserRepository users = mock(UserRepository.class);
        User user = new User(); user.setUsername("reader"); user.setPasswordHash("hash");
        Role active = new Role(); active.setCode("read_only");
        Role disabled = new Role(); disabled.setCode("system_admin"); disabled.setIsActive(false);
        user.setRoles(Set.of(active, disabled));
        when(users.findByUsername("reader")).thenReturn(Optional.of(user));
        var service = new AuthService(mock(AuthenticationManager.class), mock(JwtTokenProvider.class),
                users, mock(PasswordEncoder.class));
        assertEquals(List.of("ROLE_read_only"), service.loadUserByUsername("reader")
                .getAuthorities().stream().map(Object::toString).toList());
    }

    @Test void asyncSecurityContextIsCopiedWithoutLeakingToNextTask() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(AsyncSecurityConfig.class)) {
            var executor = (AsyncTaskExecutor) context.getBean(AsyncSecurityConfig.class).getAsyncExecutor();
            var security = SecurityContextHolder.createEmptyContext();
            security.setAuthentication(new UsernamePasswordAuthenticationToken("importer", "unused", List.of()));
            SecurityContextHolder.setContext(security);
            assertEquals("importer", executor.submit(() -> SecurityContextHolder.getContext()
                    .getAuthentication().getName()).get(5, TimeUnit.SECONDS));
            SecurityContextHolder.clearContext();
            assertNull(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication())
                    .get(5, TimeUnit.SECONDS));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
