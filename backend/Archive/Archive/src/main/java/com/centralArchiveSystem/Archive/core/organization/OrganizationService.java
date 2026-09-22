package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationRequest;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationTreeResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationUpdateRequest;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;

    public OrganizationService(OrganizationRepository organizationRepository,
                               ProjectRepository projectRepository) {
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
    }

    public OrganizationResponse getById(UUID id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));
        return toResponse(organization);
    }

    public Page<OrganizationResponse> list(String search, UUID parentId, String type,
                                           Boolean isActive, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Organization.OrganizationType orgType =
                type == null || type.isBlank()
                        ? null
                        : Organization.OrganizationType.valueOf(type.toUpperCase());

        return organizationRepository.searchFiltered(
                search,
                parentId,
                orgType,
                isActive,
                pageable
        ).map(this::toResponse);
    }

    public List<OrganizationTreeResponse> getTree() {
        List<Organization> allOrganizations = organizationRepository.findByIsActive(true);
        Map<UUID, List<Organization>> childrenMap = allOrganizations.stream()
                .filter(o -> o.getParentId() != null)
                .collect(Collectors.groupingBy(Organization::getParentId));

        return allOrganizations.stream()
                .filter(o -> o.getParentId() == null)
                .map(o -> buildTreeNode(o, childrenMap))
                .collect(Collectors.toList());
    }

    public List<OrganizationTreeResponse> getSubtree(UUID parentId) {
        Organization parent = organizationRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + parentId));

        List<Organization> allOrganizations = organizationRepository.findByIsActive(true);
        Map<UUID, List<Organization>> childrenMap = allOrganizations.stream()
                .filter(o -> o.getParentId() != null)
                .collect(Collectors.groupingBy(Organization::getParentId));

        return List.of(buildTreeNode(parent, childrenMap));
    }

    public List<OrganizationResponse> getPath(UUID id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));

        List<Organization> path = new ArrayList<>();
        Organization current = organization;

        while (current != null) {
            path.add(0, current);
            if (current.getParentId() != null) {
                current = organizationRepository.findById(current.getParentId()).orElse(null);
            } else {
                current = null;
            }
        }

        return path.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public OrganizationResponse create(OrganizationRequest request) {
        Organization.OrganizationType type = Organization.OrganizationType.valueOf(request.type().toUpperCase());

        if (organizationRepository.existsByCode(request.code())) {
            throw new IllegalArgumentException("Organization code already exists: " + request.code());
        }

        validateHierarchy(type, request.parentId(), null);

        Organization organization = new Organization();
        organization.setParentId(request.parentId());
        organization.setName(request.name());
        organization.setCode(request.code());
        organization.setType(type);
        organization.setDescription(request.description());
        organization.setIsActive(true);

        Organization saved = organizationRepository.save(organization);
        return toResponse(saved);
    }

    @Transactional
    public OrganizationResponse update(UUID id, OrganizationUpdateRequest request) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));

        if (request.name() != null && !request.name().isBlank()) {
            organization.setName(request.name());
        }
        if (request.description() != null) {
            organization.setDescription(request.description());
        }
        if (request.isActive() != null) {
            if (!request.isActive()) {
                validateDeactivation(id);
            }
            organization.setIsActive(request.isActive());
        }

        Organization saved = organizationRepository.save(organization);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));

        validateDeactivation(id);

        organization.setIsActive(false);
        organizationRepository.save(organization);
    }

    private OrganizationTreeResponse buildTreeNode(Organization org, Map<UUID, List<Organization>> childrenMap) {
        List<OrganizationTreeResponse> childNodes = childrenMap.getOrDefault(org.getId(), List.of()).stream()
                .map(child -> buildTreeNode(child, childrenMap))
                .collect(Collectors.toList());

        return new OrganizationTreeResponse(
                org.getId(),
                org.getParentId(),
                org.getName(),
                org.getCode(),
                org.getType().name(),
                org.getDescription(),
                org.getIsActive(),
                org.getCreatedAt(),
                childNodes
        );
    }

    private OrganizationResponse toResponse(Organization org) {
        long childrenCount = organizationRepository.countByParentId(org.getId());
        return new OrganizationResponse(
                org.getId(),
                org.getParentId(),
                org.getName(),
                org.getCode(),
                org.getType().name(),
                org.getDescription(),
                org.getIsActive(),
                org.getCreatedAt(),
                childrenCount,
                projectRepository.countByOrganizationId(org.getId())
        );
    }

    private void validateHierarchy(Organization.OrganizationType type, UUID parentId, UUID currentId) {
        if (type == Organization.OrganizationType.COMPANY) {
            if (parentId != null) {
                throw new IllegalArgumentException("COMPANY organizations cannot have a parent");
            }
            return;
        }

        if (parentId == null) {
            throw new IllegalArgumentException(type + " organizations require a parent");
        }
        if (currentId != null && currentId.equals(parentId)) {
            throw new IllegalArgumentException("Organization cannot be its own parent");
        }

        Organization parent = organizationRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Parent organization not found with id: " + parentId));

        if (!Boolean.TRUE.equals(parent.getIsActive())) {
            throw new IllegalArgumentException("Parent organization must be active");
        }

        Organization.OrganizationType expectedParent = switch (type) {
            case FACTORY -> Organization.OrganizationType.COMPANY;
            case LINE -> Organization.OrganizationType.FACTORY;
            case DEPARTMENT -> Organization.OrganizationType.LINE;
            case COMPANY -> null;
        };

        if (parent.getType() != expectedParent) {
            throw new IllegalArgumentException(
                    type + " must have a parent of type " + expectedParent);
        }

        if (currentId != null) {
            UUID cursor = parent.getParentId();
            Set<UUID> visited = new HashSet<>();
            visited.add(parent.getId());
            while (cursor != null) {
                if (!visited.add(cursor) || currentId.equals(cursor)) {
                    throw new IllegalArgumentException("Organization hierarchy cycle detected");
                }
                UUID ancestorId = cursor;
                Organization ancestor = organizationRepository.findById(ancestorId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Ancestor organization not found with id: " + ancestorId));
                cursor = ancestor.getParentId();
            }
        }
    }

    private void validateDeactivation(UUID id) {
        long activeChildren = organizationRepository.findByParentId(id).stream()
                .filter(child -> Boolean.TRUE.equals(child.getIsActive()))
                .count();
        if (activeChildren > 0) {
            throw new IllegalStateException(
                    "Cannot deactivate organization with active children. Deactivate children first.");
        }

        long activeProjects = projectRepository.countActiveProjectsByOrganizationId(id);
        if (activeProjects > 0) {
            throw new IllegalStateException(
                    "Cannot deactivate organization with active projects");
        }
    }
}
