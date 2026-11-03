package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.CurrentUserService;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.PagedResponse;
import com.centralArchiveSystem.Archive.project.file.dto.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class FileController {
    private final FileService fileService;
    private final CurrentUserService currentUserService;
    public FileController(FileService fileService, CurrentUserService currentUserService) {
        this.fileService=fileService; this.currentUserService=currentUserService;
    }

    @GetMapping("/api/v1/projects/{projectId}/files")
    @PreAuthorize("#status == null or !'DELETED'.equalsIgnoreCase(#status) or hasRole('system_admin')")
    public ResponseEntity<ApiResponse<PagedResponse<FileResponse>>> listProjectFiles(
            @PathVariable UUID projectId, @RequestParam(required=false) UUID categoryId,
            @RequestParam(required=false) UUID typeId, @RequestParam(defaultValue="ACTIVE") String status,
            @RequestParam(required=false) String search, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        if (!"ACTIVE".equalsIgnoreCase(status) && !"DELETED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("حالة الملف يجب أن تكون ACTIVE أو DELETED");
        }
        int limit=Math.max(1,Math.min(size,100));
        Page<FileResponse> result=fileService.listProjectFiles(projectId,categoryId,typeId,status,search,page-1,limit);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.fromSpring(result,page,limit)));
    }

    @PostMapping(value="/api/v1/projects/{projectId}/files/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry')")
    public ResponseEntity<ApiResponse<FileResponse>> uploadFile(@PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file, @Valid @RequestPart("metadata") FileUploadRequest metadata) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                fileService.uploadFile(projectId,file,metadata,currentUserService.userId()),"File uploaded successfully"));
    }

    @PostMapping(value="/api/v1/projects/{projectId}/files/upload-batch",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry')")
    public ResponseEntity<ApiResponse<FileBatchResponse>> uploadBatch(@PathVariable UUID projectId,
            @RequestParam("files") List<MultipartFile> files, @Valid @RequestPart("metadata") FileUploadRequest metadata) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                fileService.uploadBatch(projectId,files,metadata,currentUserService.userId()),"Batch upload completed"));
    }

    @GetMapping("/api/v1/files/{id}")
    public ResponseEntity<ApiResponse<FileResponse>> getFileMetadata(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileService.getFileMetadata(id)));
    }

    @PatchMapping("/api/v1/files/{id}")
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry')")
    public ResponseEntity<ApiResponse<FileResponse>> updateFileMetadata(@PathVariable UUID id,
            @Valid @RequestBody FileMetadataUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(fileService.updateFileMetadata(id,request),"File metadata updated successfully"));
    }

    @DeleteMapping("/api/v1/files/{id}")
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry')")
    public ResponseEntity<ApiResponse<Void>> softDeleteFile(@PathVariable UUID id,@RequestParam(required=false) String reason) {
        fileService.softDelete(id,currentUserService.userId(),reason);
        return ResponseEntity.ok(ApiResponse.success(null,"File deleted successfully"));
    }

    @GetMapping("/api/v1/files/{id}/download")
    @PreAuthorize("hasAnyRole('system_admin','central_data_entry','auditor','archive_officer','import_officer','export_officer','read_only')")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable UUID id) {
        FileResponse metadata=fileService.getFileMetadata(id);
        InputStream input=fileService.downloadFile(id);
        StreamingResponseBody body=output -> { try (input) { input.transferTo(output); } };
        MediaType media=MediaType.APPLICATION_OCTET_STREAM;
        if (metadata.mimeType()!=null && !metadata.mimeType().isBlank()) {
            try { media=MediaType.parseMediaType(metadata.mimeType()); }
            catch (IllegalArgumentException ignored) { /* Retain the safe binary fallback. */ }
        }
        var response=ResponseEntity.ok().contentType(media).header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(metadata.originalName(),StandardCharsets.UTF_8).build().toString());
        if (metadata.sizeBytes()!=null && metadata.sizeBytes()>=0) response.contentLength(metadata.sizeBytes());
        return response.body(body);
    }

    @PostMapping("/api/v1/files/{id}/restore")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<ApiResponse<FileResponse>> restoreFile(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(fileService.restoreFile(id),"File restored successfully"));
    }
}
