package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.project.file.FilePreviewService;
import com.centralArchiveSystem.Archive.project.file.OfficePreviewService;
import com.centralArchiveSystem.Archive.project.file.PdfPreviewService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Authenticated read-only previews of files that still live inside verified packages. */
@RestController
@RequestMapping("/api/v1/packages/{packageId}/preview/projects/{projectIndex}/files/{fileIndex}")
@PreAuthorize("hasAnyRole('system_admin', 'import_officer')")
public class PackageFilePreviewController {
    private final PackageFilePreviewService preview;

    public PackageFilePreviewController(PackageFilePreviewService preview) {
        this.preview = preview;
    }

    private PackageFilePreviewService.PreviewFile read(UUID packageId, int projectIndex, int fileIndex) {
        return preview.read(packageId, projectIndex, fileIndex);
    }

    private HttpHeaders safeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        return headers;
    }

    private void requireKind(PackageFilePreviewService.PreviewFile file, FilePreviewService.Kind kind) {
        if (FilePreviewService.classify(file.originalName(), file.mimeType()) != kind) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "نوع الملف لا يدعم هذه المعاينة");
        }
    }

    @GetMapping
    public ResponseEntity<byte[]> raw(@PathVariable UUID packageId,
                                      @PathVariable int projectIndex,
                                      @PathVariable int fileIndex) {
        var file = read(packageId, projectIndex, fileIndex);
        if ("pdf".equals(file.extension())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "معاينة PDF متاحة عبر صفحات مصورة");
        }
        if (FilePreviewService.classify(file.originalName(), file.mimeType())
                == FilePreviewService.Kind.OFFICE) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "افتح هذا المستند من خلال معاينة Office");
        }
        HttpHeaders headers = safeHeaders();
        if (FilePreviewService.classify(file.originalName(), file.mimeType())
                == FilePreviewService.Kind.TEXT) {
            headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        } else {
            headers.setContentType(MediaType.parseMediaType(file.mimeType()));
        }
        headers.setContentLength(file.bytes().length);
        headers.setContentDisposition(ContentDisposition.inline().filename(file.originalName(),
                StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(file.bytes(), headers, HttpStatus.OK);
    }

    @GetMapping("/office")
    public ResponseEntity<OfficePreviewService.Preview> office(@PathVariable UUID packageId,
                                                               @PathVariable int projectIndex,
                                                               @PathVariable int fileIndex) throws Exception {
        var file = read(packageId, projectIndex, fileIndex);
        requireKind(file, FilePreviewService.Kind.OFFICE);
        try {
            var result = OfficePreviewService.parse(file.bytes(), file.extension());
            return new ResponseEntity<>(result, safeHeaders(), HttpStatus.OK);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر قراءة محتوى مستند Office داخل الحزمة", e);
        }
    }

    private PackageFilePreviewService.PreviewFile pdf(UUID packageId, int projectIndex, int fileIndex) {
        var file = read(packageId, projectIndex, fileIndex);
        requireKind(file, FilePreviewService.Kind.INLINE);
        if (!"pdf".equals(file.extension())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "هذا الملف ليس PDF");
        }
        return file;
    }

    @GetMapping("/pdf/info")
    public ResponseEntity<PdfPreviewService.PdfInfo> pdfInfo(@PathVariable UUID packageId,
                                                              @PathVariable int projectIndex,
                                                              @PathVariable int fileIndex) {
        var file = pdf(packageId, projectIndex, fileIndex);
        return new ResponseEntity<>(PdfPreviewService.inspectBytes(file.bytes()),
                safeHeaders(), HttpStatus.OK);
    }

    @GetMapping("/pdf/pages/{page}")
    public ResponseEntity<byte[]> pdfPage(@PathVariable UUID packageId,
                                          @PathVariable int projectIndex,
                                          @PathVariable int fileIndex,
                                          @PathVariable int page) {
        var file = pdf(packageId, projectIndex, fileIndex);
        byte[] png = PdfPreviewService.renderPackagePdfPage(file.bytes(), page);
        HttpHeaders headers = safeHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentLength(png.length);
        return new ResponseEntity<>(png, headers, HttpStatus.OK);
    }
}
