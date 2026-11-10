package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
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

    /**
     * Every organization at one level, or at every level when {@code parentId} is absent, for a
     * form to offer as a choice. Paging a selection list is not an option: the page boundary would
     * fall between two rows of the same dropdown without anything on screen saying so.
     */
    public List<OrganizationResponse> listAll(UUID parentId, String type, Boolean isActive) {
        Organization.OrganizationType orgType =
                type == null || type.isBlank()
                        ? null
                        : Organization.OrganizationType.valueOf(type.toUpperCase());

        // The type is narrowed here rather than in the query because the alternatives are four
        // near-identical repository methods for a table this small, and an unmatched value has to
        // fail the same way it does on the paginated endpoint either way.
        return organizationRepository.findAllForSelection(parentId, isActive).stream()
                .filter(org -> orgType == null || org.getType() == orgType)
                .map(this::toResponse)
                .collect(Collectors.toList());
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
            // ConflictException rather than IllegalArgumentException, and this is the reason it
            // matters: a check that loses a race is caught by the unique index instead, and the
            // database path answers 409. Throwing anything else here would make the same mistake
            // return 400 or 409 depending on timing.
            throw new ConflictException("Organization", "code", request.code());
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
        if (request.parentId() != null && !request.parentId().equals(organization.getParentId())) {
            validateHierarchy(organization.getType(), request.parentId(), id);
            organization.setParentId(request.parentId());
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
                throw new IllegalArgumentException("مؤسسات COMPANY لا يمكن أن يكون لها مؤسسة أم");
            }
            return;
        }

        if (parentId == null) {
            throw new IllegalArgumentException("مؤسسات " + type + " يجب أن يكون لها مؤسسة أم");
        }
        if (currentId != null && currentId.equals(parentId)) {
            throw new IllegalArgumentException("لا يمكن للمؤسسة أن تكون المؤسسة الأم لنفسها");
        }

        Organization parent = organizationRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("المؤسسة الأم غير موجودة"));

        if (!Boolean.TRUE.equals(parent.getIsActive())) {
            throw new IllegalArgumentException("يجب أن تكون المؤسسة الأم مُفعّلة");
        }

        Organization.OrganizationType expectedParent = switch (type) {
            case FACTORY -> Organization.OrganizationType.COMPANY;
            case LINE -> Organization.OrganizationType.FACTORY;
            case DEPARTMENT -> Organization.OrganizationType.LINE;
            case COMPANY -> null;
        };

        if (parent.getType() != expectedParent) {
            throw new IllegalArgumentException(
                    "مؤسسات " + type + " يجب أن يكون لها مؤسسة أم من نوع " + expectedParent);
        }

        if (currentId != null) {
            UUID cursor = parent.getParentId();
            Set<UUID> visited = new HashSet<>();
            visited.add(parent.getId());
            while (cursor != null) {
                if (!visited.add(cursor) || currentId.equals(cursor)) {
                    throw new IllegalArgumentException("تم اكتشاف دورة في تسلسل المؤسسات");
                }
                UUID ancestorId = cursor;
                Organization ancestor = organizationRepository.findById(ancestorId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "إحدى المؤسسات الأم غير موجودة"));
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
