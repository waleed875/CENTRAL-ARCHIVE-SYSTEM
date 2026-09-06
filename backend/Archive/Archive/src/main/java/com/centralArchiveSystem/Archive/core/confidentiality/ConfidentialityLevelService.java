package com.centralArchiveSystem.Archive.core.confidentiality;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelRequest;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelResponse;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConfidentialityLevelService {

    private final ConfidentialityLevelRepository confidentialityLevelRepository;
    private final ProjectRepository projectRepository;
    private final FileRepository fileRepository;

    public ConfidentialityLevelService(ConfidentialityLevelRepository confidentialityLevelRepository,
                                       ProjectRepository projectRepository,
                                       FileRepository fileRepository) {
        this.confidentialityLevelRepository = confidentialityLevelRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
    }

    public Page<ConfidentialityLevelResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("levelOrder").ascending());
        return confidentialityLevelRepository.findAll(pageable).map(this::toResponse);
    }

    public ConfidentialityLevelResponse getById(UUID id) {
        ConfidentialityLevel level = confidentialityLevelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConfidentialityLevel", "id", id));
        return toResponse(level);
    }

    @Transactional
    public ConfidentialityLevelResponse create(ConfidentialityLevelRequest request) {
        confidentialityLevelRepository.findByCode(request.code())
                .ifPresent(existing -> {
                    throw new ConflictException("ConfidentialityLevel", "code", request.code());
                });

        confidentialityLevelRepository.findByLevelOrder(request.levelOrder())
                .ifPresent(existing -> {
                    throw new ConflictException("ConfidentialityLevel", "levelOrder", request.levelOrder());
                });

        ConfidentialityLevel level = new ConfidentialityLevel();
        level.setCode(request.code());
        level.setName(request.name());
        level.setLevelOrder(request.levelOrder());
        level.setDescription(request.description());

        ConfidentialityLevel saved = confidentialityLevelRepository.save(level);
        return toResponse(saved);
    }

    @Transactional
    public ConfidentialityLevelResponse update(UUID id, ConfidentialityLevelRequest request) {
        ConfidentialityLevel level = confidentialityLevelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConfidentialityLevel", "id", id));

        if (request.code() != null && !request.code().isBlank()) {
            confidentialityLevelRepository.findByCode(request.code())
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ConflictException("ConfidentialityLevel", "code", request.code());
                    });
            level.setCode(request.code());
        }

        if (request.levelOrder() != null) {
            confidentialityLevelRepository.findByLevelOrder(request.levelOrder())
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ConflictException("ConfidentialityLevel", "levelOrder", request.levelOrder());
                    });
            level.setLevelOrder(request.levelOrder());
        }

        if (request.name() != null && !request.name().isBlank()) {
            level.setName(request.name());
        }
        if (request.description() != null) {
            level.setDescription(request.description());
        }

        ConfidentialityLevel saved = confidentialityLevelRepository.save(level);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ConfidentialityLevel level = confidentialityLevelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConfidentialityLevel", "id", id));

        long projectsCount = projectRepository.countByConfidentialityLevelId(id);
        if (projectsCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete confidentiality level that is referenced by " + projectsCount + " project(s)");
        }

        long filesCount = fileRepository.countByConfidentialityLevelIdAndDeletedAtIsNull(id);
        if (filesCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete confidentiality level that is referenced by " + filesCount + " file(s)");
        }

        confidentialityLevelRepository.delete(level);
    }

    private ConfidentialityLevelResponse toResponse(ConfidentialityLevel level) {
        long projectsCount = projectRepository.countByConfidentialityLevelId(level.getId());
        long filesCount = fileRepository.countByConfidentialityLevelIdAndDeletedAtIsNull(level.getId());

        return new ConfidentialityLevelResponse(
                level.getId(),
                level.getCode(),
                level.getName(),
                level.getLevelOrder(),
                level.getDescription(),
                level.getCreatedAt(),
                projectsCount,
                filesCount,
                0L
        );
    }
}
