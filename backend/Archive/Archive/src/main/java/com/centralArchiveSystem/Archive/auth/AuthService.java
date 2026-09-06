package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.auth.dto.ChangePasswordRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginResponse;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.core.user.User.UserStatus;
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

import java.time.LocalDateTime;
import java.util.List;
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

        String token = tokenProvider.generateToken((UserDetails) authentication.getPrincipal());
        String refreshToken = tokenProvider.generateRefreshToken((UserDetails) authentication.getPrincipal());

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toList()),
                user.getMaxConfidentialityLevelId()
        );

        return new LoginResponse(
                token,
                tokenProvider.getExpirationMs() / 1000,
                userInfo
        );
    }

    public void logout() {
        SecurityContextHolder.clearContext();
    }

    @Transactional
    public LoginResponse refreshToken(String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String username = tokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        UserDetails userDetails = loadUserByUsername(username);
        String newToken = tokenProvider.generateToken(userDetails);
        String newRefreshToken = tokenProvider.generateRefreshToken(userDetails);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toList()),
                user.getMaxConfidentialityLevelId()
        );

        return new LoginResponse(
                newToken,
                tokenProvider.getExpirationMs() / 1000,
                userInfo
        );
    }

    public LoginResponse.UserInfo getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toList()),
                user.getMaxConfidentialityLevelId()
        );
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
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }
}
