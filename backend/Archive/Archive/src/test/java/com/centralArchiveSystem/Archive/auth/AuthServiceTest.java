package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logoutPersistsTokenRevocationCutoff() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        Authentication authentication = mock(Authentication.class);

        User user = new User();
        user.setUsername("auditor");
        user.setStatus(User.UserStatus.ACTIVE);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("auditor");
        when(userRepository.findByUsername("auditor")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        AuthService service = new AuthService(
                authenticationManager,
                tokenProvider,
                userRepository,
                passwordEncoder
        );

        service.logout();

        assertNotNull(user.getTokenValidAfter());
        verify(userRepository).save(user);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void revokedRefreshTokenCannotBeReused() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        User user = new User();
        user.setUsername("auditor");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setTokenValidAfter(java.time.Instant.now());

        when(tokenProvider.validateRefreshToken("refresh-token")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("refresh-token")).thenReturn("auditor");
        when(tokenProvider.isIssuedOnOrAfter("refresh-token", user.getTokenValidAfter())).thenReturn(false);
        when(userRepository.findByUsername("auditor")).thenReturn(Optional.of(user));

        AuthService service = new AuthService(
                authenticationManager,
                tokenProvider,
                userRepository,
                passwordEncoder
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.refreshToken("refresh-token"));
        verify(userRepository, never()).save(any());
        verify(tokenProvider, never()).generateToken(any());
        verify(tokenProvider, never()).generateRefreshToken(any());
    }
}
