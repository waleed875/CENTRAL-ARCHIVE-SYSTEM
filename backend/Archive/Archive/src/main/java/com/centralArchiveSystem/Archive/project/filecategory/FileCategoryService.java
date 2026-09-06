package com.centralArchiveSystem.Archive.project.filecategory;

import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryRequest;
import com.centralArchiveSystem.Archive.project.filecategory.dto.FileCategoryResponse;
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
        Page<FileCategory> categories;

        if (search != null && !search.isBlank()) {
            categories = fileCategoryRepository.searchByNameOrCode(search, pageable);
        } else {
            categories = fileCategoryRepository.findAll(pageable);
        }

        if (isActive != null) {
            categories = categories.getContent().stream()
                    .filter(c -> c.getIsActive().equals(isActive))
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())
                    ));
        }

        return categories.map(this::toResponse);
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
    public FileCategoryResponse update(UUID id, FileCategoryRequest request) {
        FileCategory category = fileCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));

        if (request.code() != null && !request.code().isBlank() &&
            fileCategoryRepository.existsByCodeAndIdNot(request.code(), id)) {
            throw new IllegalArgumentException("File category code already exists: " + request.code());
        }

        if (request.code() != null) category.setCode(request.code());
        if (request.name() != null) category.setName(request.name());
        if (request.description() != null) category.setDescription(request.description());
        if (request.isActive() != null) category.setIsActive(request.isActive());

        FileCategory saved = fileCategoryRepository.save(category);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        FileCategory category = fileCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("File category not found with id: " + id));

        long filesCount = fileCategoryRepository.countFilesByCategoryId(id);
        if (filesCount > 0) {
            throw new IllegalStateException("Cannot delete category with existing files. Remove files first.");
        }

        fileCategoryRepository.delete(category);
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
