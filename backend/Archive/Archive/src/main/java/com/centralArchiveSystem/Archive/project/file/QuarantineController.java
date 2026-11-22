package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.UUID;

/**
 * Deliberately separate from ordinary file endpoints. Quarantined bytes are
 * only available as an inert .bin attachment to a system administrator for
 * manual offline review, never as an inline or browser-executable resource.
 */
@RestController
@RequestMapping("/api/v1/admin/quarantine")
@PreAuthorize("hasRole('system_admin')")
public class QuarantineController {
    private final QuarantineService quarantine;

    public QuarantineController(QuarantineService quarantine) {
        this.quarantine = quarantine;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<QuarantineService.Entry>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = quarantine.list(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                PagedResponse.fromSpring(result, result.getNumber() + 1, result.getSize())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<QuarantineService.Entry>> metadata(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(quarantine.get(id)));
    }

    @GetMapping("/{id}/reviews")
    public ResponseEntity<ApiResponse<java.util.List<QuarantineService.HistoryEntry>>> history(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(quarantine.history(id)));
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<ApiResponse<QuarantineService.ReviewResult>> review(
            @PathVariable UUID id, @RequestBody QuarantineService.ReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.success(quarantine.review(id, request)));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadForOfflineReview(@PathVariable UUID id) {
        // The status, project and file confidentiality are verified again in
        // openForForensicReview. The original executable suffix is never used
        // in Content-Disposition, preventing inadvertent browser execution.
        QuarantineService.Entry entry = quarantine.get(id);
        InputStream input = quarantine.openForForensicReview(id);
        StreamingResponseBody body = output -> {
            try (input) { input.transferTo(output); }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("quarantine-" + id + ".bin").build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("X-Archive-Quarantine", "review-required");
        headers.setContentLength(entry.sizeBytes());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
