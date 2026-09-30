package com.centralArchiveSystem.Archive.core.confidentiality;

import com.centralArchiveSystem.Archive.common.exception.ConflictException;
import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelRequest;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelResponse;
import com.centralArchiveSystem.Archive.core.confidentiality.dto.ConfidentialityLevelUpdateRequest;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ConfidentialityLevelService {

    private final ConfidentialityLevelRepository confidentialityLevelRepository;
    private final ProjectRepository projectRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    public ConfidentialityLevelService(ConfidentialityLevelRepository confidentialityLevelRepository,
                                       ProjectRepository projectRepository,
                                       FileRepository fileRepository,
                                       UserRepository userRepository) {
        this.confidentialityLevelRepository = confidentialityLevelRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
    }

    public Page<ConfidentialityLevelResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("levelOrder").ascending());
        return confidentialityLevelRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Every level in severity order, unpaginated, for a form to offer as a choice.
     *
     * <p>Separate from {@link #list} rather than a large {@code size} on it, because a size cap
     * would be a limit nobody could see: the form would offer the first twenty levels and quietly
     * refuse the rest, and the value that got dropped is the one the user was entitled to pick.
     */
    public List<ConfidentialityLevelResponse> listAll() {
        return confidentialityLevelRepository.findAllByOrderByLevelOrderAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
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
    public ConfidentialityLevelResponse update(UUID id, ConfidentialityLevelUpdateRequest request) {
        ConfidentialityLevel level = confidentialityLevelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConfidentialityLevel", "id", id));

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

        long filesCount = fileRepository.countByConfidentialityLevelId(id);
        if (filesCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete confidentiality level that is referenced by " + filesCount + " file(s)");
        }

        long usersCount = userRepository.countByMaxConfidentialityLevelId(id);
        if (usersCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete confidentiality level that is referenced by " + usersCount + " user(s)");
        }

        confidentialityLevelRepository.delete(level);
    }

    private ConfidentialityLevelResponse toResponse(ConfidentialityLevel level) {
        long projectsCount = projectRepository.countByConfidentialityLevelId(level.getId());
        long filesCount = fileRepository.countByConfidentialityLevelId(level.getId());
        long usersCount = userRepository.countByMaxConfidentialityLevelId(level.getId());

        return new ConfidentialityLevelResponse(
                level.getId(),
                level.getCode(),
                level.getName(),
                level.getLevelOrder(),
                level.getDescription(),
                level.getCreatedAt(),
                projectsCount,
                filesCount,
                usersCount
        );
    }
}
