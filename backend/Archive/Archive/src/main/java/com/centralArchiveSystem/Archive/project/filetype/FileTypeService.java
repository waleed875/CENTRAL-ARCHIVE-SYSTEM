package com.centralArchiveSystem.Archive.project.filetype;

import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeRequest;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeResponse;
import com.centralArchiveSystem.Archive.project.filetype.dto.FileTypeUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
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
public class FileTypeService {

    private final FileTypeRepository fileTypeRepository;
    private final FileCategoryRepository fileCategoryRepository;

    public FileTypeService(FileTypeRepository fileTypeRepository,
                           FileCategoryRepository fileCategoryRepository) {
        this.fileTypeRepository = fileTypeRepository;
        this.fileCategoryRepository = fileCategoryRepository;
    }

    public Page<FileTypeResponse> list(UUID categoryId, String search, Boolean isActive, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return fileTypeRepository.searchFiltered(search, categoryId, isActive, pageable)
                .map(this::toResponse);
    }

    public List<FileTypeResponse> listByCategory(UUID categoryId, Boolean isActive) {
        List<FileType> types;
        if (isActive != null) {
            types = fileTypeRepository.findByCategoryIdAndIsActive(categoryId, isActive);
        } else {
            types = fileTypeRepository.findByCategoryId(categoryId);
        }
        return types.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Every type across every category, unpaginated, for a form that offers types before a
     * category is chosen. Once a category is known, {@link #listByCategory} is the narrower and
     * cheaper call.
     */
    public List<FileTypeResponse> listAll(Boolean isActive) {
        return fileTypeRepository.findAllForSelection(isActive).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public FileTypeResponse getById(UUID id) {
        FileType fileType = fileTypeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File type not found with id: " + id));
        return toResponse(fileType);
    }

    @Transactional
    public FileTypeResponse create(FileTypeRequest request) {
        FileCategory category = fileCategoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + request.categoryId()));

        if (fileTypeRepository.existsByCode(request.code())) {
            throw new IllegalArgumentException("File type code already exists: " + request.code());
        }

        FileType fileType = new FileType();
        fileType.setCategoryId(request.categoryId());
        fileType.setCode(request.code());
        fileType.setName(request.name());
        fileType.setDefaultExtension(request.defaultExtension());
        fileType.setDescription(request.description());
        fileType.setIsActive(request.isActive() != null ? request.isActive() : true);

        FileType saved = fileTypeRepository.save(fileType);
        return toResponse(saved);
    }

    @Transactional
    public FileTypeResponse update(UUID id, FileTypeUpdateRequest request) {
        FileType fileType = fileTypeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File type not found with id: " + id));

        if (request.name() != null && !request.name().isBlank()) {
            fileType.setName(request.name());
        }
        if (request.defaultExtension() != null && !request.defaultExtension().isBlank()) {
            fileType.setDefaultExtension(request.defaultExtension());
        }
        if (request.description() != null) {
            fileType.setDescription(request.description());
        }
        if (request.isActive() != null) {
            if (!request.isActive() && fileTypeRepository.countActiveFilesByTypeId(id) > 0) {
                throw new IllegalStateException("Cannot deactivate file type with active files");
            }
            fileType.setIsActive(request.isActive());
        }

        FileType saved = fileTypeRepository.save(fileType);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        FileType fileType = fileTypeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File type not found with id: " + id));

        long activeFilesCount = fileTypeRepository.countActiveFilesByTypeId(id);
        if (activeFilesCount > 0) {
            throw new IllegalStateException("Cannot deactivate file type with active files");
        }

        fileType.setIsActive(false);
        fileTypeRepository.save(fileType);
    }

    private FileTypeResponse toResponse(FileType fileType) {
        FileCategory category = fileCategoryRepository.findById(fileType.getCategoryId()).orElse(null);
        long filesCount = fileTypeRepository.countFilesByTypeId(fileType.getId());

        return new FileTypeResponse(
                fileType.getId(),
                fileType.getCategoryId(),
                category != null ? category.getCode() : null,
                category != null ? category.getName() : null,
                fileType.getCode(),
                fileType.getName(),
                fileType.getDefaultExtension(),
                fileType.getDescription(),
                fileType.getIsActive(),
                fileType.getCreatedAt(),
                fileType.getUpdatedAt(),
                filesCount
        );
    }
}
