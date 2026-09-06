package com.centralArchiveSystem.Archive.core.user;

import com.centralArchiveSystem.Archive.common.dto.PageRequest;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.dto.ChangePasswordRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserRequest;
import com.centralArchiveSystem.Archive.core.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public PagedResponse<UserResponse> list(String search, UUID organizationId, User.UserStatus status, PageRequest pageRequest) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
            pageRequest.getPage() - 1,
            pageRequest.getLimit(),
            Sort.by(Sort.Direction.fromString(pageRequest.getOrder()), pageRequest.getSort())
        );

        Page<User> page;

        if (search != null && !search.isBlank()) {
            page = userRepository.searchByUsernameFullNameEmail(search, pageable);
        } else if (organizationId != null) {
            List<User> users = userRepository.findByOrganizationId(organizationId);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), users.size());
            List<User> sublist = start < users.size() ? users.subList(start, end) : List.of();
            page = new org.springframework.data.domain.PageImpl<>(sublist, pageable, users.size());
        } else if (status != null) {
            List<User> users = userRepository.findByStatus(status);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), users.size());
            List<User> sublist = start < users.size() ? users.subList(start, end) : List.of();
            page = new org.springframework.data.domain.PageImpl<>(sublist, pageable, users.size());
        } else {
            page = userRepository.findAll(pageable);
        }

        List<UserResponse> content = page.getContent().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        return PagedResponse.of(content, pageRequest, page.getTotalElements());
    }

    public UserResponse getById(UUID id) {
        User user = findUserOrThrow(id);
        return toResponse(user);
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("User", "username", request.username());
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setOrganizationId(request.organizationId());
        user.setMaxConfidentialityLevelId(request.maxConfidentialityLevelId());
        user.setStatus(User.UserStatus.ACTIVE);

        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            Set<Role> roles = new HashSet<>(roleRepository.findAllById(request.roleIds()));
            user.setRoles(roles);
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(UUID id, UserRequest request) {
        User user = findUserOrThrow(id);

        if (request.username() != null && !request.username().equals(user.getUsername())) {
            if (userRepository.existsByUsernameAndIdNot(request.username(), id)) {
                throw new ConflictException("User", "username", request.username());
            }
            user.setUsername(request.username());
        }

        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }

        if (request.email() != null) {
            user.setEmail(request.email());
        }

        if (request.organizationId() != null) {
            user.setOrganizationId(request.organizationId());
        }

        if (request.maxConfidentialityLevelId() != null) {
            user.setMaxConfidentialityLevelId(request.maxConfidentialityLevelId());
        }

        if (request.roleIds() != null) {
            Set<Role> roles = new HashSet<>(roleRepository.findAllById(request.roleIds()));
            user.setRoles(roles);
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public void delete(UUID id) {
        User user = findUserOrThrow(id);
        user.setStatus(User.UserStatus.DISABLED);
        userRepository.save(user);
    }

    public Set<UserResponse.RoleSummary> getRoles(UUID userId) {
        User user = findUserOrThrow(userId);
        return user.getRoles().stream()
            .map(role -> new UserResponse.RoleSummary(
                role.getId(),
                role.getName(),
                role.getDescription()
            ))
            .collect(Collectors.toSet());
    }

    @Transactional
    public void addRole(UUID userId, UUID roleId) {
        User user = findUserOrThrow(userId);
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException("Role", "id", roleId));
        user.getRoles().add(role);
        userRepository.save(user);
    }

    @Transactional
    public void removeRole(UUID userId, UUID roleId) {
        User user = findUserOrThrow(userId);
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException("Role", "id", roleId));
        user.getRoles().remove(role);
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = findUserOrThrow(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private UserResponse toResponse(User user) {
        UserResponse.OrganizationSummary orgSummary = null;
        if (user.getOrganization() != null) {
            orgSummary = new UserResponse.OrganizationSummary(
                user.getOrganization().getId(),
                user.getOrganization().getName(),
                user.getOrganization().getCode()
            );
        }

        Set<UserResponse.RoleSummary> roleSummaries = user.getRoles().stream()
            .map(role -> new UserResponse.RoleSummary(
                role.getId(),
                role.getName(),
                role.getDescription()
            ))
            .collect(Collectors.toSet());

        return new UserResponse(
            user.getId(),
            user.getUsername(),
            user.getFullName(),
            user.getEmail(),
            user.getStatus().name(),
            user.getOrganizationId(),
            user.getMaxConfidentialityLevelId(),
            user.getCreatedAt(),
            user.getUpdatedAt(),
            orgSummary,
            roleSummaries,
            null
        );
    }
}
