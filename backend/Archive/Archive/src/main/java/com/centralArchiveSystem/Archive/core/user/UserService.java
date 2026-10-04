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
import com.centralArchiveSystem.Archive.core.user.dto.UserUpdateRequest;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperation;
import com.centralArchiveSystem.Archive.import_.operation.ImportOperationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ImportOperationRepository importOperationRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       ImportOperationRepository importOperationRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.importOperationRepository = importOperationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public PagedResponse<UserResponse> list(String search, UUID organizationId, User.UserStatus status,
                                            String roleCode, PageRequest pageRequest) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
            pageRequest.getPage() - 1,
            pageRequest.getLimit(),
            Sort.by(Sort.Direction.fromString(pageRequest.getOrder()), pageRequest.getSort())
        );

        Page<User> page = userRepository.searchFiltered(
                search,
                organizationId,
                status,
                roleCode,
                pageable
        );

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
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new ConflictException("User", "email", request.email());
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
            user.setRoles(resolveRoles(request.roleIds()));
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(UUID id, UserUpdateRequest request) {
        User user = findUserOrThrow(id);

        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName());
        }

        if (request.email() != null) {
            if (!request.email().isBlank() && userRepository.existsByEmailAndIdNot(request.email(), id)) {
                throw new ConflictException("User", "email", request.email());
            }
            user.setEmail(request.email().isBlank() ? null : request.email());
        }

        if (request.organizationId() != null) {
            user.setOrganizationId(request.organizationId());
        }

        if (request.maxConfidentialityLevelId() != null) {
            user.setMaxConfidentialityLevelId(request.maxConfidentialityLevelId());
        }

        if (request.status() != null) {
            if (request.status() != User.UserStatus.ACTIVE) {
                user.setTokenValidAfter(Instant.now());
            }
            user.setStatus(request.status());
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public void delete(UUID id) {
        User user = findUserOrThrow(id);
        long activeImports = importOperationRepository.countByImportedByAndStatus(
                id,
                ImportOperation.Status.STARTED
        );
        if (activeImports > 0) {
            throw new BusinessRuleException(
                    "لا يمكن تعطيل المستخدم أثناء وجود عمليات استيراد قيد التنفيذ");
        }
        user.setStatus(User.UserStatus.DISABLED);
        user.setTokenValidAfter(Instant.now());
        userRepository.save(user);
    }

    public Set<UserResponse.RoleSummary> getRoles(UUID userId) {
        User user = findUserOrThrow(userId);
        return user.getRoles().stream()
            .map(role -> new UserResponse.RoleSummary(
                role.getId(),
                role.getCode(),
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
        if (!Boolean.TRUE.equals(role.getIsActive())) {
            throw new BusinessRuleException("لا يمكن إسناد دور مُعطّل");
        }
        if (user.getRoles().stream().anyMatch(existing -> roleId.equals(existing.getId()))) {
            throw new ConflictException("UserRole", "role_id", roleId);
        }
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
            throw new BusinessRuleException("كلمة المرور الحالية غير صحيحة");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    private Set<Role> resolveRoles(Set<UUID> roleIds) {
        List<Role> found = roleRepository.findAllById(roleIds);
        if (found.size() != roleIds.size()) {
            throw new ResourceNotFoundException("Role", "id", "one or more requested role IDs");
        }
        if (found.stream().anyMatch(role -> !Boolean.TRUE.equals(role.getIsActive()))) {
            throw new BusinessRuleException("لا يمكن إسناد أدوار مُعطّلة");
        }
        return new HashSet<>(found);
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
                role.getCode(),
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
