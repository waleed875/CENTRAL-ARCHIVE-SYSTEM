package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.auth.dto.ChangePasswordRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginResponse;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.User.UserStatus;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class AuthService implements UserDetailsService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(@Lazy AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(user.getRoles().stream()
                        .map(role -> "ROLE_" + role.getCode())
                        .toArray(String[]::new))
                .accountExpired(false)
                .accountLocked(user.getStatus() != UserStatus.ACTIVE)
                .credentialsExpired(false)
                .disabled(user.getStatus() != UserStatus.ACTIVE)
                .build();
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + request.username()));

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return buildLoginResponse(user, userDetails);
    }

    @Transactional
    public void logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            userRepository.findByUsername(authentication.getName()).ifPresent(user -> {
                user.setTokenValidAfter(Instant.now());
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);
            });
        }
        SecurityContextHolder.clearContext();
    }

    @Transactional(readOnly = true)
    public LoginResponse refreshToken(String refreshToken) {
        if (!tokenProvider.validateRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String username = tokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("User account is not active");
        }
        if (!tokenProvider.isIssuedOnOrAfter(refreshToken, user.getTokenValidAfter())) {
            throw new IllegalArgumentException("Refresh token has been revoked");
        }

        // Rotate the token family: all previously issued access/refresh tokens
        // for this user become invalid before the new pair is minted.
        user.setTokenValidAfter(Instant.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        UserDetails userDetails = loadUserByUsername(username);
        return buildLoginResponse(user, userDetails);
    }

    @Transactional(readOnly = true)
    public LoginResponse.UserInfo getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return toUserInfo(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenValidAfter(Instant.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private LoginResponse buildLoginResponse(User user, UserDetails userDetails) {
        String accessToken = tokenProvider.generateToken(userDetails);
        String refreshToken = tokenProvider.generateRefreshToken(userDetails);

        return new LoginResponse(
                accessToken,
                refreshToken,
                tokenProvider.getExpirationMs() / 1000,
                tokenProvider.getRefreshExpirationMs() / 1000,
                toUserInfo(user)
        );
    }

    private LoginResponse.UserInfo toUserInfo(User user) {
        return new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toList()),
                user.getMaxConfidentialityLevelId()
        );
    }
}
