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

    // Keep the explicit StreamingResponseBody generic type: Spring MVC needs it to
    // select its asynchronous streaming handler. ResponseEntity<?> causes the
    // HttpMessageConverter path to be selected and can return HTTP 500 for PDF/video.
    @GetMapping("/api/v1/files/{id}/preview")
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry','auditor','archive_officer','import_officer','export_officer','read_only')")
    public ResponseEntity<StreamingResponseBody> preview(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String requestedRange) {
        // Metadata and both stream methods apply the same file/project confidentiality checks.
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
            byte[] bytes = previews.officeText(id, FilePreviewService.extension(metadata.originalName()))
                    .getBytes(StandardCharsets.UTF_8);
            headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
            headers.setContentLength(bytes.length);
            return new ResponseEntity<>(output -> output.write(bytes), headers, HttpStatus.OK);
        }

        if (kind == FilePreviewService.Kind.TEXT) {
            headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        } else {
            headers.setContentType(MediaType.parseMediaType(metadata.mimeType()));
        }

        boolean media = kind == FilePreviewService.Kind.INLINE &&
                (metadata.mimeType().startsWith("video/") || metadata.mimeType().startsWith("audio/"));
        long total = metadata.sizeBytes() != null ? metadata.sizeBytes() : -1L;
        // Native <video>/<audio> controls request byte ranges for seeking and metadata.
        // Do not honor ranges for text or Office previews.
        if (media && requestedRange != null && total > 0) {
            ByteRange range = parseSingleRange(requestedRange, total);
            InputStream input = files.downloadFileRange(id, range.start(), range.length());
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + total);
            headers.setContentLength(range.length());
            StreamingResponseBody body = output -> {
                try (input) { input.transferTo(output); }
            };
            return new ResponseEntity<>(body, headers, HttpStatus.PARTIAL_CONTENT);
        }

        InputStream input = files.downloadFile(id);
        if (media) headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (total >= 0) headers.setContentLength(total);
        StreamingResponseBody body = output -> {
            try (input) { input.transferTo(output); }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    static ByteRange parseSingleRange(String header, long total) {
        if (header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0 || total <= 0) {
            throw invalidRange();
        }
        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-')) throw invalidRange();
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            if (a.isEmpty()) {
                if (b.isEmpty()) throw invalidRange();
                long suffix = Long.parseLong(b);
                if (suffix <= 0) throw invalidRange();
                long start = total - Math.min(total, suffix);
                return new ByteRange(start, total - 1);
            }
            long start = Long.parseLong(a);
            long end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
            if (start < 0 || start >= total || end < start) throw invalidRange();
            return new ByteRange(start, end);
        } catch (NumberFormatException e) {
            throw invalidRange();
        }
    }

    private static ResponseStatusException invalidRange() {
        return new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                "نطاق البايتات المطلوب غير صالح");
    }

    record ByteRange(long start, long end) {
        long length() { return end - start + 1; }
    }
}
