package com.centralArchiveSystem.Archive.project.projecttype;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeRequest;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeResponse;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeUpdateRequest;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ProjectTypeService {

    private final ProjectTypeRepository projectTypeRepository;
    private final ProjectRepository projectRepository;

    public ProjectTypeService(ProjectTypeRepository projectTypeRepository,
                              ProjectRepository projectRepository) {
        this.projectTypeRepository = projectTypeRepository;
        this.projectRepository = projectRepository;
    }

    public Page<ProjectTypeResponse> list(Boolean isActive, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("name").ascending());

        Page<ProjectType> types = isActive != null
                ? projectTypeRepository.findByIsActive(isActive, pageRequest)
                : projectTypeRepository.findAll(pageRequest);

        return types.map(this::toResponse);
    }

    public List<ProjectTypeResponse> listAll() {
        return projectTypeRepository.findByIsActive(true).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ProjectTypeResponse getById(UUID id) {
        ProjectType projectType = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectType", "id", id));
        return toResponse(projectType);
    }

    @Transactional
    public ProjectTypeResponse create(ProjectTypeRequest request) {
        if (projectTypeRepository.existsByCode(request.code())) {
            throw new ConflictException("ProjectType", "code", request.code());
        }

        ProjectType projectType = new ProjectType();
        projectType.setCode(request.code());
        projectType.setName(request.name());
        projectType.setDescription(request.description());
        projectType.setIsActive(true);

        ProjectType saved = projectTypeRepository.save(projectType);
        return toResponse(saved);
    }

    @Transactional
    public ProjectTypeResponse update(UUID id, ProjectTypeUpdateRequest request) {
        ProjectType projectType = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectType", "id", id));

        if (request.name() != null && !request.name().isBlank()) {
            projectType.setName(request.name());
        }
        if (request.description() != null) {
            projectType.setDescription(request.description());
        }
        if (request.isActive() != null) {
            projectType.setIsActive(request.isActive());
        }

        ProjectType saved = projectTypeRepository.save(projectType);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ProjectType projectType = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectType", "id", id));

        projectType.setIsActive(false);
        projectTypeRepository.save(projectType);
    }

    private ProjectTypeResponse toResponse(ProjectType projectType) {
        return new ProjectTypeResponse(
                projectType.getId(),
                projectType.getCode(),
                projectType.getName(),
                projectType.getDescription(),
                projectType.getIsActive(),
                projectType.getCreatedAt(),
                projectRepository.countLatestByProjectTypeId(projectType.getId())
        );
    }
}
