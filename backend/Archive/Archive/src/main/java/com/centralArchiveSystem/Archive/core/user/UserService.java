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

import com.centralArchiveSystem.Archive.auth.TokenRevocation;
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
        userRepository.lockAdministration();
        validateReferences(request.organizationId(), request.maxConfidentialityLevelId());
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
        // Keep the non-null display-name column compatible with existing consumers while
        // allowing account creation to omit a separate full name.
        user.setFullName(request.fullName() == null || request.fullName().isBlank()
                ? request.username() : request.fullName().trim());
        user.setEmail(request.email() == null || request.email().isBlank() ? null : request.email());
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
        userRepository.lockAdministration();
        User user = findForUpdate(id);

        validateReferences(request.organizationId(), request.maxConfidentialityLevelId());
        if (request.username() != null && !request.username().equals(user.getUsername())) {
            String nextUsername = request.username().trim();
            if (userRepository.existsByUsernameAndIdNot(nextUsername, id)) {
                throw new ConflictException("User", "username", nextUsername);
            }
            user.setUsername(nextUsername);
            TokenRevocation.advance(user);
        }
        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName());
        }

        if (request.email() != null) {
            if (!request.email().isBlank() && userRepository.existsByEmailAndIdNot(request.email(), id)) {
                throw new ConflictException("User", "email", request.email());
            }
            user.setEmail(request.email().isBlank() ? null : request.email());
        }

        if (request.organizationId() != null
                && !request.organizationId().equals(user.getOrganizationId())) {
            user.setOrganizationId(request.organizationId());
            TokenRevocation.advance(user);
        }

        if (Boolean.TRUE.equals(request.clearConfidentialityLevel()) && request.maxConfidentialityLevelId() != null) {
            throw new BusinessRuleException("لا يمكن تحديد مستوى سرية وإزالته في الطلب نفسه");
        }
        if (Boolean.TRUE.equals(request.clearConfidentialityLevel())) {
            if (user.getMaxConfidentialityLevelId() != null) {
                user.setMaxConfidentialityLevelId(null);
                TokenRevocation.advance(user);
            }
        } else if (request.maxConfidentialityLevelId() != null
                && !request.maxConfidentialityLevelId().equals(user.getMaxConfidentialityLevelId())) {
            user.setMaxConfidentialityLevelId(request.maxConfidentialityLevelId());
            TokenRevocation.advance(user);
        }

        if (request.status() != null) {
            if (request.status() != User.UserStatus.ACTIVE) {
                requireAnotherAdministrator(user, null);
                requireNoActiveImports(id);
                TokenRevocation.advance(user);
            }
            user.setStatus(request.status());
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateOwnDisplayName(UUID id, String fullName) {
        User user = findForUpdate(id);
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessRuleException("الحساب غير نشط");
        }
        user.setFullName(fullName.trim());
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void delete(UUID id) {
        userRepository.lockAdministration();
        User user = findForUpdate(id);
        requireAnotherAdministrator(user, null);
        requireNoActiveImports(id);
        user.setStatus(User.UserStatus.DISABLED);
        TokenRevocation.advance(user);
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
        userRepository.lockAdministration();
        User user = findForUpdate(userId);
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException("Role", "id", roleId));
        if (!Boolean.TRUE.equals(role.getIsActive())) {
            throw new BusinessRuleException("لا يمكن إسناد دور مُعطّل");
        }
        if (user.getRoles().stream().anyMatch(existing -> roleId.equals(existing.getId()))) {
            throw new ConflictException("UserRole", "role_id", roleId);
        }
        user.getRoles().add(role);
        TokenRevocation.advance(user);
        userRepository.save(user);
    }

    @Transactional
    public void removeRole(UUID userId, UUID roleId) {
        userRepository.lockAdministration();
        User user = findForUpdate(userId);
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException("Role", "id", roleId));
        requireAnotherAdministrator(user, roleId);
        user.getRoles().remove(role);
        TokenRevocation.advance(user);
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        userRepository.lockAdministration();
        User user = findForUpdate(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("كلمة المرور الحالية غير صحيحة");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        TokenRevocation.advance(user);
        userRepository.save(user);
    }

    @Transactional
    public void resetPassword(UUID userId, com.centralArchiveSystem.Archive.core.user.dto.ResetPasswordRequest request) {
        userRepository.lockAdministration();
        User user = findForUpdate(userId);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        TokenRevocation.advance(user);
        userRepository.save(user);
    }

    private User findForUpdate(UUID id) {
        return userRepository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private void requireNoActiveImports(UUID id) {
        if (importOperationRepository.countByImportedByAndStatus(id, ImportOperation.Status.STARTED) > 0) {
            throw new BusinessRuleException("لا يمكن تعطيل المستخدم أثناء وجود عمليات استيراد قيد التنفيذ");
        }
    }

    private void requireAnotherAdministrator(User user, UUID removedRoleId) {
        if (user.getStatus() != User.UserStatus.ACTIVE) return;
        for (Role role : user.getRoles()) {
            if (!"system_admin".equals(role.getCode()) || !Boolean.TRUE.equals(role.getIsActive())) continue;
            if (removedRoleId != null && !removedRoleId.equals(role.getId())) continue;
            if (userRepository.countActiveUsersByRoleId(role.getId()) <= 1) {
                throw new BusinessRuleException("لا يمكن تعطيل آخر مدير نشط أو سحب دوره. عين مديرا آخر أولا");
            }
        }
    }

    private void validateReferences(UUID organizationId, UUID clearanceId) {
        if (organizationId != null) {
            var organization = userRepository.findOrganization(organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", organizationId));
            if (!Boolean.TRUE.equals(organization.getIsActive())) {
                throw new BusinessRuleException("المؤسسة المحددة معطلة");
            }
        }
        if (clearanceId != null && userRepository.findClearance(clearanceId).isEmpty()) {
            throw new ResourceNotFoundException("ConfidentialityLevel", "id", clearanceId);
        }
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
        UserResponse.OrganizationSummary orgSummary = user.getOrganizationId() == null ? null
                : userRepository.findOrganization(user.getOrganizationId())
                        .map(org -> new UserResponse.OrganizationSummary(org.getId(), org.getName(), org.getCode()))
                        .orElse(null);
        UserResponse.ConfidentialityLevelSummary clearance = user.getMaxConfidentialityLevelId() == null ? null
                : userRepository.findClearance(user.getMaxConfidentialityLevelId())
                        .map(level -> new UserResponse.ConfidentialityLevelSummary(level.getId(), level.getName(), level.getLevelOrder()))
                        .orElse(null);

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
            clearance
        );
    }
}
