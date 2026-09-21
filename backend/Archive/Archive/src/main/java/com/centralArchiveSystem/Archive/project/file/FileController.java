package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;\nimport com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.file.dto.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
public class FileController {

    private final FileService fileService;
    private final CurrentUserService currentUserService;

    public FileController(FileService fileService, CurrentUserService currentUserService) {
        this.fileService = fileService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/v1/projects/{projectId}/files")
    public ResponseEntity<ApiResponse<PagedResponse<FileResponse>>> listProjectFiles(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileResponse> result = fileService.listProjectFiles(projectId, categoryId, typeId, search, page - 1, size);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result, page, size)));
    }

    @PostMapping("/api/v1/projects/{projectId}/files/upload")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<FileResponse>> uploadFile(
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @Valid @RequestPart("metadata") FileUploadRequest metadata) {
        FileResponse response = fileService.uploadFile(projectId, file, metadata, currentUserService.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "File uploaded successfully"));
    }

    @PostMapping("/api/v1/projects/{projectId}/files/upload-batch")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<FileBatchResponse>> uploadBatch(
            @PathVariable UUID projectId,
            @RequestParam("files") List<MultipartFile> files,
            @Valid @RequestPart("metadata") FileUploadRequest metadata) {
        FileBatchResponse response = fileService.uploadBatch(projectId, files, metadata, currentUserService.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Batch upload completed"));
    }

    @GetMapping("/api/v1/files/{id}")
    public ResponseEntity<ApiResponse<FileResponse>> getFileMetadata(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileService.getFileMetadata(id)));
    }

    @PatchMapping("/api/v1/files/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<FileResponse>> updateFileMetadata(
            @PathVariable UUID id,
            @Valid @RequestBody FileUploadRequest request) {
        return ResponseEntity.ok(ApiResponse.success(fileService.updateFileMetadata(id, request), "File metadata updated successfully"));
    }

    @DeleteMapping("/api/v1/files/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<Void>> softDeleteFile(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        fileService.softDelete(id, currentUserService.userId(), reason);
        return ResponseEntity.ok(ApiResponse.success(null, "File deleted successfully"));
    }

    @GetMapping("/api/v1/files/{id}/download")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry', 'auditor', 'archive_officer', 'import_officer', 'export_officer')")
    public void downloadFile(@PathVariable UUID id, HttpServletResponse response) {
        try {
            InputStream inputStream = fileService.downloadFile(id);
            FileResponse fileMetadata = fileService.getFileMetadata(id);

            response.setContentType(fileMetadata.mimeType() != null ? fileMetadata.mimeType() : "application/octet-stream");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileMetadata.originalName() + "\"");
            response.setContentLength(fileMetadata.sizeBytes().intValue());

            inputStream.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
            inputStream.close();
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/api/v1/files/{id}/restore")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<FileResponse>> restoreFile(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileService.restoreFile(id), "File restored successfully"));
    }
}
