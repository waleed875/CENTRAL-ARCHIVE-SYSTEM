package com.centralArchiveSystem.Archive.project.filecategory;

import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryRequest;
import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryResponse;
import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryUpdateRequest;
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
public class FileCategoryService {

    private final FileCategoryRepository fileCategoryRepository;

    public FileCategoryService(FileCategoryRepository fileCategoryRepository) {
        this.fileCategoryRepository = fileCategoryRepository;
    }

    public Page<FileCategoryResponse> list(String search, Boolean isActive, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return fileCategoryRepository.searchFiltered(search, isActive, pageable)
                .map(this::toResponse);
    }

    public List<FileCategoryResponse> listAll(Boolean isActive) {
        List<FileCategory> categories;
        if (isActive != null) {
            categories = fileCategoryRepository.findByIsActive(isActive);
        } else {
            categories = fileCategoryRepository.findAll();
        }
        return categories.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public FileCategoryResponse getById(UUID id) {
        FileCategory category = fileCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));
        return toResponse(category);
    }

    @Transactional
    public FileCategoryResponse create(FileCategoryRequest request) {
        if (fileCategoryRepository.existsByCode(request.code())) {
            throw new IllegalArgumentException("File category code already exists: " + request.code());
        }

        FileCategory category = new FileCategory();
        category.setCode(request.code());
        category.setName(request.name());
        category.setDescription(request.description());
        category.setIsActive(request.isActive() != null ? request.isActive() : true);

        FileCategory saved = fileCategoryRepository.save(category);
        return toResponse(saved);
    }

    @Transactional
    public FileCategoryResponse update(UUID id, FileCategoryUpdateRequest request) {
        FileCategory category = fileCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));

        if (request.name() != null && !request.name().isBlank()) {
            category.setName(request.name());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }
        if (request.isActive() != null) {
            if (!request.isActive() && fileCategoryRepository.countActiveFilesByCategoryId(id) > 0) {
                throw new IllegalStateException("Cannot deactivate category with active files");
            }
            category.setIsActive(request.isActive());
        }

        FileCategory saved = fileCategoryRepository.save(category);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        FileCategory category = fileCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));

        long activeFilesCount = fileCategoryRepository.countActiveFilesByCategoryId(id);
        if (activeFilesCount > 0) {
            throw new IllegalStateException("Cannot deactivate category with active files");
        }

        category.setIsActive(false);
        fileCategoryRepository.save(category);
    }

    private FileCategoryResponse toResponse(FileCategory category) {
        long filesCount = fileCategoryRepository.countFilesByCategoryId(category.getId());
        return new FileCategoryResponse(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.getDescription(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                filesCount
        );
    }
}
