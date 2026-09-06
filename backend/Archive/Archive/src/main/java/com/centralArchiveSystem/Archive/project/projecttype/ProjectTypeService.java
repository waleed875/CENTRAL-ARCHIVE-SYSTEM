package com.centralArchiveSystem.Archive.project.projecttype;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeRequest;
import com.centralArchiveSystem.Archive.project.projecttype.dto.ProjectTypeResponse;
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

    public ProjectTypeService(ProjectTypeRepository projectTypeRepository) {
        this.projectTypeRepository = projectTypeRepository;
    }

    public Page<ProjectTypeResponse> list(Boolean isActive, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("name").ascending());

        List<ProjectType> types;
        if (isActive != null) {
            types = projectTypeRepository.findByIsActive(isActive);
        } else {
            types = projectTypeRepository.findAll();
        }

        long total = types.size();
        List<ProjectTypeResponse> content = types.stream()
                .skip((long) page * size)
                .limit(size)
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new org.springframework.data.domain.PageImpl<>(content, pageRequest, total);
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
    public ProjectTypeResponse update(UUID id, ProjectTypeRequest request) {
        ProjectType projectType = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectType", "id", id));

        if (request.code() != null && !request.code().isBlank() &&
            projectTypeRepository.existsByCodeAndIdNot(request.code(), id)) {
            throw new ConflictException("ProjectType", "code", request.code());
        }

        if (request.code() != null) projectType.setCode(request.code());
        if (request.name() != null) projectType.setName(request.name());
        if (request.description() != null) projectType.setDescription(request.description());

        ProjectType saved = projectTypeRepository.save(projectType);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ProjectType projectType = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectType", "id", id));

        projectTypeRepository.delete(projectType);
    }

    private ProjectTypeResponse toResponse(ProjectType projectType) {
        return new ProjectTypeResponse(
                projectType.getId(),
                projectType.getCode(),
                projectType.getName(),
                projectType.getDescription(),
                projectType.getIsActive(),
                projectType.getCreatedAt()
        );
    }
}
