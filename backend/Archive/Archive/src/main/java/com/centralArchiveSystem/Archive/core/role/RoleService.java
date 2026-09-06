package com.centralArchiveSystem.Archive.core.role;

import com.centralArchiveSystem.Archive.common.dto.PageRequest;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.common.exception.BusinessRuleException;
import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.role.dto.RoleRequest;
import com.centralArchiveSystem.Archive.core.role.dto.RoleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RoleService {

    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    public PagedResponse<RoleResponse> list(String search, Boolean isActive, PageRequest pageRequest) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
            pageRequest.getPage() - 1,
            pageRequest.getLimit(),
            Sort.by(Sort.Direction.fromString(pageRequest.getOrder()), pageRequest.getSort())
        );

        Page<Role> page;

        if (search != null && !search.isBlank()) {
            page = roleRepository.searchByNameOrCode(search, pageable);
        } else if (isActive != null) {
            List<Role> roles = roleRepository.findByIsActive(isActive);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), roles.size());
            List<Role> sublist = start < roles.size() ? roles.subList(start, end) : List.of();
            page = new org.springframework.data.domain.PageImpl<>(sublist, pageable, roles.size());
        } else {
            page = roleRepository.findAll(pageable);
        }

        List<RoleResponse> content = page.getContent().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        return PagedResponse.of(content, pageRequest, page.getTotalElements());
    }

    public RoleResponse getById(UUID id) {
        Role role = findRoleOrThrow(id);
        return toResponse(role);
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
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
    public RoleResponse update(UUID id, RoleRequest request) {
        Role role = findRoleOrThrow(id);

        if (request.code() != null && !request.code().equals(role.getCode())) {
            if (roleRepository.existsByCodeAndIdNot(request.code(), id)) {
                throw new ConflictException("Role", "code", request.code());
            }
            role.setCode(request.code());
        }

        if (request.name() != null) {
            role.setName(request.name());
        }

        if (request.description() != null) {
            role.setDescription(request.description());
        }

        role = roleRepository.save(role);
        return toResponse(role);
    }

    @Transactional
    public void delete(UUID id) {
        Role role = findRoleOrThrow(id);

        if (Boolean.TRUE.equals(role.getIsActive())) {
            throw new BusinessRuleException("Cannot deactivate an active role. Deactivate all users with this role first.");
        }

        roleRepository.delete(role);
    }

    @Transactional
    public void softDeactivate(UUID id) {
        Role role = findRoleOrThrow(id);
        role.setIsActive(false);
        roleRepository.save(role);
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
            null
        );
    }
}
