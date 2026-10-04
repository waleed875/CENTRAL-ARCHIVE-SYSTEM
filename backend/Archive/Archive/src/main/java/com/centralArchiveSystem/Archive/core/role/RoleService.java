package com.centralArchiveSystem.Archive.core.role;

import com.centralArchiveSystem.Archive.common.dto.PageRequest;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.role.dto.RoleRequest;
import com.centralArchiveSystem.Archive.core.role.dto.RoleResponse;
import com.centralArchiveSystem.Archive.core.role.dto.RoleUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RoleService {

    private static final Set<String> MVP_ROLE_CODES = Set.of(
            "system_admin",
            "central_data_entry",
            "auditor",
            "archive_officer",
            "import_officer",
            "export_officer",
            "read_only"
    );

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public RoleService(RoleRepository roleRepository, UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    public PagedResponse<RoleResponse> list(String search, Boolean isActive, PageRequest pageRequest) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
            pageRequest.getPage() - 1,
            pageRequest.getLimit(),
            Sort.by(Sort.Direction.fromString(pageRequest.getOrder()), pageRequest.getSort())
        );

        Page<Role> page = roleRepository.searchFiltered(search, isActive, pageable);

        List<RoleResponse> content = page.getContent().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        return PagedResponse.of(content, pageRequest, page.getTotalElements());
    }

    /**
     * Every role, unpaginated, for a form to offer as a choice.
     *
     * <p>Separate from {@link #list} because a selection list has to be complete: paging it would
     * leave a user able to pick only the roles on the page the request happened to return.
     */
    public List<RoleResponse> listAll(Boolean isActive) {
        return roleRepository.findAllForSelection(isActive).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public RoleResponse getById(UUID id) {
        Role role = findRoleOrThrow(id);
        return toResponse(role);
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        if (!MVP_ROLE_CODES.contains(request.code())) {
            throw new BusinessRuleException(
                    "رمز الدور غير مدعوم: " + request.code());
        }
        if (roleRepository.existsByCode(request.code())) {
            throw new ConflictException("Role", "code", request.code());
        }

        Role role = new Role();
        role.setCode(request.code());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setIsActive(true);

        role = roleRepository.save(role);
        return toResponse(role);
    }

    @Transactional
    public RoleResponse update(UUID id, RoleUpdateRequest request) {
        Role role = findRoleOrThrow(id);

        if (request.name() != null && !request.name().isBlank()) {
            role.setName(request.name());
        }
        if (request.description() != null) {
            role.setDescription(request.description());
        }
        if (request.isActive() != null) {
            if (!request.isActive()) {
                ensureNoActiveUsers(role.getId());
            }
            role.setIsActive(request.isActive());
        }

        role = roleRepository.save(role);
        return toResponse(role);
    }

    @Transactional
    public void delete(UUID id) {
        Role role = findRoleOrThrow(id);
        ensureNoActiveUsers(id);
        role.setIsActive(false);
        roleRepository.save(role);
    }

    private void ensureNoActiveUsers(UUID roleId) {
        long activeUsers = userRepository.countActiveUsersByRoleId(roleId);
        if (activeUsers > 0) {
            throw new BusinessRuleException(
                    "لا يمكن تعطيل دور مُسنَد إلى مستخدمين مُفعّلين");
        }
    }

    private Role findRoleOrThrow(UUID id) {
        return roleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Role", "id", id));
    }

    private RoleResponse toResponse(Role role) {
        return new RoleResponse(
            role.getId(),
            role.getCode(),
            role.getName(),
            role.getDescription(),
            role.getIsActive(),
            role.getCreatedAt(),
            userRepository.countActiveUsersByRoleId(role.getId())
        );
    }
}
