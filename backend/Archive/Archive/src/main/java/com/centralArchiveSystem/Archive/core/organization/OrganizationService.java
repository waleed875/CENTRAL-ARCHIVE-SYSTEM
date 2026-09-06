package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationRequest;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationResponse;
import com.centralArchiveSystem.Archive.core.organization.dto.OrganizationTreeResponse;
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

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    public OrganizationResponse getById(UUID id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));
        return toResponse(organization);
    }

    public Page<OrganizationResponse> list(String search, String type, Boolean isActive, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());

        Page<Organization> organizations;

        if (search != null && !search.isBlank()) {
            organizations = organizationRepository.searchByNameOrCode(search, pageable);
        } else {
            organizations = organizationRepository.findAll(pageable);
        }

        if (type != null && !type.isBlank()) {
            Organization.OrganizationType orgType = Organization.OrganizationType.valueOf(type.toUpperCase());
            organizations = organizations.getContent().stream()
                    .filter(o -> o.getType() == orgType)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())
                    ));
        }

        if (isActive != null) {
            organizations = organizations.getContent().stream()
                    .filter(o -> o.getIsActive().equals(isActive))
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())
                    ));
        }

        return organizations.map(this::toResponse);
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
    public OrganizationResponse update(UUID id, OrganizationRequest request) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));

        if (request.code() != null && !request.code().isBlank() &&
            organizationRepository.existsByCodeAndIdNot(request.code(), id)) {
            throw new IllegalArgumentException("Organization code already exists: " + request.code());
        }

        if (request.name() != null) organization.setName(request.name());
        if (request.code() != null) organization.setCode(request.code());
        if (request.type() != null) {
            organization.setType(Organization.OrganizationType.valueOf(request.type().toUpperCase()));
        }
        if (request.description() != null) organization.setDescription(request.description());
        if (request.parentId() != null) organization.setParentId(request.parentId());

        Organization saved = organizationRepository.save(organization);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organization not found with id: " + id));

        long childrenCount = organizationRepository.countByParentId(id);
        if (childrenCount > 0) {
            throw new IllegalStateException("Cannot deactivate organization with active children. Deactivate children first.");
        }

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
                0
        );
    }
}
