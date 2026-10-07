package com.centralArchiveSystem.Archive.project.member;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.project.member.dto.MemberCreateRequest;
import com.centralArchiveSystem.Archive.project.member.dto.MemberUpdateRequest;
import com.centralArchiveSystem.Archive.project.member.dto.MemberResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    public ProjectMemberController(ProjectMemberService projectMemberService) {
        this.projectMemberService = projectMemberService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MemberResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.success(projectMemberService.listByProjectId(projectId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<MemberResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody MemberCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projectMemberService.create(projectId, request), "Member added"));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<MemberResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(projectMemberService.update(projectId, id, request), "Member updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('system_admin', 'central_data_entry')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        projectMemberService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
