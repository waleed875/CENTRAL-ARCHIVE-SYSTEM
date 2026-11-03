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
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class AuthService implements UserDetailsService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(@Lazy AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider, UserRepository userRepository,
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
                .username(user.getUsername()).password(user.getPasswordHash())
                .authorities(user.getRoles().stream()
                        .filter(role -> Boolean.TRUE.equals(role.getIsActive()))
                        .map(role -> "ROLE_" + role.getCode()).toArray(String[]::new))
                .accountExpired(false).accountLocked(user.getStatus() != UserStatus.ACTIVE)
                .credentialsExpired(false).disabled(user.getStatus() != UserStatus.ACTIVE).build();
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // Serialize credential verification with resets so an old password cannot race a reset.
        userRepository.findForUpdateByUsername(request.username());
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + request.username()));
        return buildLoginResponse(user, (UserDetails) authentication.getPrincipal());
    }

    @Transactional
    public void logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            userRepository.findForUpdateByUsername(authentication.getName()).ifPresent(user -> {
                TokenRevocation.advance(user);
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);
            });
        }
        SecurityContextHolder.clearContext();
    }

    @Transactional
    public LoginResponse refreshToken(String refreshToken) {
        if (!tokenProvider.validateRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("رمز التحديث غير صالح");
        }
        String username = tokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findForUpdateByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("حساب المستخدم غير مُفعّل");
        }
        if (!tokenProvider.isIssuedOnOrAfter(refreshToken, user.getTokenValidAfter())) {
            throw new IllegalArgumentException("تم إلغاء رمز التحديث");
        }
        TokenRevocation.advance(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return buildLoginResponse(user, loadUserByUsername(username));
    }

    @Transactional(readOnly = true)
    public LoginResponse.UserInfo getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return toUserInfo(userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username)));
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findForUpdateByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("كلمة المرور الحالية غير صحيحة");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        TokenRevocation.advance(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private LoginResponse buildLoginResponse(User user, UserDetails userDetails) {
        return new LoginResponse(tokenProvider.generateToken(userDetails, user.getTokenValidAfter()),
                tokenProvider.generateRefreshToken(userDetails, user.getTokenValidAfter()), tokenProvider.getExpirationMs() / 1000,
                tokenProvider.getRefreshExpirationMs() / 1000, toUserInfo(user));
    }

    private LoginResponse.UserInfo toUserInfo(User user) {
        return new LoginResponse.UserInfo(user.getId(), user.getUsername(), user.getFullName(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                user.getRoles().stream().filter(role -> Boolean.TRUE.equals(role.getIsActive()))
                        .map(role -> role.getCode()).collect(Collectors.toList()),
                user.getMaxConfidentialityLevelId());
    }
}
