package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.auth.ConfidentialityAccessService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.project.dto.ProjectResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class ReviewQueueController {
    private final ReviewQueueRepository repository;
    private final ConfidentialityAccessService confidentiality;

    public ReviewQueueController(ReviewQueueRepository repository,
                                 ConfidentialityAccessService confidentiality) {
        this.repository = repository;
        this.confidentiality = confidentiality;
    }

    @GetMapping("/api/v1/projects/review-queue")
    @PreAuthorize("hasAnyRole('system_admin', 'auditor')")
    @Transactional(readOnly = true)
    @Operation(summary = "Review queue: oldest submission first; undated legacy rows last")
    public ApiResponse<PagedResponse<ProjectResponse>> waiting(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID submittedBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("رقم الصفحة وحجمها يجب أن يكونا موجبَين");
        }
        int effectiveSize = Math.min(size, 100);
        Page<ProjectResponse> result = repository.findWaiting(organizationId, submittedBy,
                        confidentiality.currentMaxLevelOrder(), PageRequest.of(page - 1, effectiveSize))
                .map(ProjectResponse::fromEntity);
        return ApiResponse.success(PagedResponse.fromSpring(result, page, effectiveSize));
    }
}
