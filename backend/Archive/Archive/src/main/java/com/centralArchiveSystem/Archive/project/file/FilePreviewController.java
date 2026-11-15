package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.project.file.dto.FileResponse;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
public class FilePreviewController {
    private final FileService files;
    private final FilePreviewService previews;

    public FilePreviewController(FileService files, FilePreviewService previews) {
        this.files = files;
        this.previews = previews;
    }

    @GetMapping("/api/v1/files/{id}/preview")
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry','auditor','archive_officer','import_officer','export_officer','read_only')")
    public ResponseEntity<?> preview(@PathVariable UUID id) {
        // Metadata and stream access enforce the same project/file confidentiality rules as download.
        FileResponse metadata = files.getFileMetadata(id);
        FilePreviewService.Kind kind = FilePreviewService.classify(metadata.originalName(), metadata.mimeType());
        if (kind == FilePreviewService.Kind.UNSUPPORTED) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "لا تتوفر معاينة لهذا النوع؛ يمكنك تنزيل الملف");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(metadata.originalName(), StandardCharsets.UTF_8).build());
        if (kind == FilePreviewService.Kind.OFFICE) {
            String content = previews.officeText(id, FilePreviewService.extension(metadata.originalName()));
            headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        }
        if (kind == FilePreviewService.Kind.TEXT) {
            headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        } else {
            headers.setContentType(MediaType.parseMediaType(metadata.mimeType()));
        }
        InputStream input = files.downloadFile(id);
        StreamingResponseBody body = output -> {
            try (input) { input.transferTo(output); }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
