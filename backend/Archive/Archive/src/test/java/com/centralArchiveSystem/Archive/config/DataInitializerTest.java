package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    @Test
    void disabledBootstrapDoesNotCreateReactivateOrReassignAdmin() throws Exception {
        RoleRepository roles = mock(RoleRepository.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        when(roles.existsByCode(anyString())).thenReturn(true);

        DataInitializer initializer = new DataInitializer(roles, users, encoder);
        setField(initializer, "bootstrapAdminEnabled", false);
        setField(initializer, "bootstrapAdminUsername", "admin");
        setField(initializer, "bootstrapAdminPassword", "unused");
        setField(initializer, "bootstrapAdminEmail", "admin@example.test");

        initializer.run();

        verify(users, never()).findByUsername(anyString());
        verify(users, never()).save(any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
