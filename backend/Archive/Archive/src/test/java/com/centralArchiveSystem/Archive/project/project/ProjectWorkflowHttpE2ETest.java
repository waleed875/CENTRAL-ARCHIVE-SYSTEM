package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategory;
import com.centralArchiveSystem.Archive.project.filecategory.FileCategoryRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectWorkflowHttpE2ETest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired FileRepository fileRepository;
    @Autowired FileCategoryRepository fileCategoryRepository;

    private final String password = "WorkflowE2EPassword123!";
    private String dataEntryUsername;
    private String auditorUsername;
    private String adminUsername;
    private UUID organizationId;

    @BeforeEach
    void setupFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        dataEntryUsername = "workflow_entry_" + suffix;
        auditorUsername = "workflow_auditor_" + suffix;
        adminUsername = "workflow_admin_" + suffix;

        Role dataEntryRole = roleRepository.findByCode("central_data_entry").orElseGet(() -> {
            Role role = new Role();
            role.setCode("central_data_entry");
            role.setName("Central Data Entry");
            role.setIsActive(true);
            return roleRepository.save(role);
        });

        Role auditorRole = roleRepository.findByCode("auditor").orElseGet(() -> {
            Role role = new Role();
            role.setCode("auditor");
            role.setName("Auditor");
            role.setIsActive(true);
            return roleRepository.save(role);
        });

        User dataEntry = new User();
        dataEntry.setUsername(dataEntryUsername);
        dataEntry.setPasswordHash(passwordEncoder.encode(password));
        dataEntry.setFullName("Workflow Data Entry");
        dataEntry.setStatus(User.UserStatus.ACTIVE);
        dataEntry.setRoles(Set.of(dataEntryRole));
        userRepository.save(dataEntry);

        User auditor = new User();
        auditor.setUsername(auditorUsername);
        auditor.setPasswordHash(passwordEncoder.encode(password));
        auditor.setFullName("Workflow Auditor");
        auditor.setStatus(User.UserStatus.ACTIVE);
        auditor.setRoles(Set.of(auditorRole));
        userRepository.save(auditor);

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Workflow System Admin");
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(roleRepository.findByCode("system_admin").orElseThrow()));
        userRepository.save(admin);

        Organization organization = new Organization();
        organization.setCode("WF-" + suffix);
        organization.setName("Workflow Organization " + suffix);
        organization.setType(Organization.OrganizationType.COMPANY);
        organization.setIsActive(true);
        organizationId = organizationRepository.save(organization).getId();
    }

    @Test
    void completeProjectMovesThroughCorrectionAndApprovalWithMutationLocks() throws Exception {
        String entryToken = login(dataEntryUsername);
        String auditorToken = login(auditorUsername);

        String createBody = """
                {
                  "organizationId":"%s",
                  "projectNumber":"WF-%s",
                  "name":"Workflow E2E Project",
                  "year":2026,
                  "description":"initial description",
                  "dataCompletionStatus":"COMPLETE"
                }
                """.formatted(
                organizationId,
                UUID.randomUUID().toString().substring(0, 8)
        );

        String createResponse = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(
                objectMapper.readTree(createResponse).path("data").path("id").asText()
        );

        FileEntity file = new FileEntity();
        file.setProjectId(projectId);
        file.setOriginalName("workflow.txt");
        file.setStoredName(UUID.randomUUID() + ".txt");
        file.setBucketName("archive-files-ci");
        file.setObjectKey("e2e/" + projectId + "/workflow.txt");
        file.setExtension("txt");
        file.setMimeType("text/plain");
        file.setSizeBytes(1L);
        file.setStatus("ACTIVE");
        file.setSourceType("MANUAL");
        UUID fileId = fileRepository.save(file).getId();

        String firstSubmit = mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", projectId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"))
                .andReturn().getResponse().getContentAsString();

        // Who sent it, and when. "Under review" carried no age before these existed, so a project
        // submitted this morning and one waiting since last month read identically.
        JsonNode submitted = objectMapper.readTree(firstSubmit).path("data");
        assertFalse(submitted.path("submittedAt").isNull(), "submittedAt must reach the client, not only the table");
        User sender = userRepository.findByUsername(dataEntryUsername).orElseThrow();
        assertEquals(sender.getId().toString(), submitted.path("submittedBy").asText());

        String memberBody = """
                {"person_name":"Locked Member","role_name":"Reviewer"}
                """;

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", projectId)
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberBody))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(delete("/api/v1/files/{id}", fileId)
                        .header("Authorization", "Bearer " + entryToken)
                        .param("reason", "should be locked"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/projects/{id}/request-correction", projectId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"review_notes\":\"Please add a member\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"))
                .andExpect(jsonPath("$.data.reviewNotes").value("Please add a member"));

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", projectId)
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberBody))
                .andExpect(status().isCreated());

        assertEquals(1, projectMemberRepository.findByProjectId(projectId).size());

        // The resubmission is the moment the note used to die: submitForReview cleared review_notes,
        // and the person clears it by finishing the work the note asked for. It has to survive.
        mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", projectId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data.reviewNotes").value("Please add a member"));

        Project resubmitted = projectRepository.findById(projectId).orElseThrow();
        assertEquals("Please add a member", resubmitted.getReviewNotes(),
                "the correction note must still be there after resubmitting");
        assertEquals(sender.getId(), resubmitted.getSubmittedBy(),
                "a resubmission re-stamps who is waiting on the project now");

        mockMvc.perform(patch("/api/v1/projects/{id}/approve", projectId)
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"forbidden after approval\"}"))
                .andExpect(status().isUnprocessableEntity());

        Project persisted = projectRepository.findById(projectId).orElseThrow();
        assertEquals(Project.ProjectStatus.APPROVED, persisted.getStatus());
        assertNotNull(persisted.getApprovedBy());
        assertNotNull(persisted.getApprovedAt());

        mockMvc.perform(patch("/api/v1/projects/{id}/revoke-approval", projectId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"reviewer cannot revoke approval\"}"))
                .andExpect(status().isForbidden());

        String adminToken = login(adminUsername);
        mockMvc.perform(patch("/api/v1/projects/{id}/revoke-approval", projectId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"A correction is required\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"))
                .andExpect(jsonPath("$.data.approvedAt").isEmpty())
                .andExpect(jsonPath("$.data.approvedBy").isEmpty())
                .andExpect(jsonPath("$.data.reviewNotes").value("A correction is required"));

        Project reversed = projectRepository.findById(projectId).orElseThrow();
        assertEquals(Project.ProjectStatus.NEEDS_CORRECTION, reversed.getStatus());
        assertNull(reversed.getApprovedBy());
        assertNull(reversed.getApprovedAt());
    }

    @Test
    void incompleteProjectCannotEnterReviewOverHttp() throws Exception {
        String entryToken = login(dataEntryUsername);

        String createBody = """
                {
                  "organizationId":"%s",
                  "projectNumber":"WF-INCOMPLETE-%s",
                  "name":"Incomplete Workflow Project",
                  "year":2026,
                  "dataCompletionStatus":"INCOMPLETE"
                }
                """.formatted(
                organizationId,
                UUID.randomUUID().toString().substring(0, 8)
        );

        String createResponse = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(
                objectMapper.readTree(createResponse).path("data").path("id").asText()
        );

        mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", projectId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(
                Project.ProjectStatus.DRAFT,
                projectRepository.findById(projectId).orElseThrow().getStatus()
        );
    }

    @Test
    void rejectionAndCorrectionKeepSeparateRecordsOverHttp() throws Exception {
        String entryToken = login(dataEntryUsername);
        String auditorToken = login(auditorUsername);

        String createResponse = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"%s",
                                  "projectNumber":"WF-REJECT-%s",
                                  "name":"Rejected Workflow Project",
                                  "year":2026,
                                  "dataCompletionStatus":"COMPLETE"
                                }
                                """.formatted(organizationId, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).path("data").path("id").asText());

        mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", projectId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk());

        // Both used to land in review_notes, so the second overwrote the first and a reason read
        // back as a correction note.
        mockMvc.perform(patch("/api/v1/projects/{id}/reject", projectId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rejection_reason":"belongs to another organization"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("belongs to another organization"));

        Project persisted = projectRepository.findById(projectId).orElseThrow();
        assertEquals("belongs to another organization", persisted.getRejectionReason());
        assertNull(persisted.getReviewNotes(),
                "a rejection must not leave its reason sitting in the correction-notes column");
    }

    @Test
    void rejectedVersionCanBeReopenedOrSupersededAndDraftSuccessorCanBeDeleted() throws Exception {
        String entryToken = login(dataEntryUsername);
        String auditorToken = login(auditorUsername);
        String createResponse = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"%s",
                                  "projectNumber":"WF-RECOVERY-%s",
                                  "name":"Version Recovery Project",
                                  "year":2026,
                                  "description":"before rejection",
                                  "dataCompletionStatus":"COMPLETE"
                                }
                                """.formatted(organizationId, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(objectMapper.readTree(createResponse).path("data").path("id").asText());

        mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", firstId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/projects/{id}/reject", firstId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejection_reason\":\"first decision\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/projects/{id}/reopen", firstId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"))
                .andExpect(jsonPath("$.data.rejectionReason").value("first decision"));

        mockMvc.perform(patch("/api/v1/projects/{id}", firstId)
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"corrected after reopening\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("corrected after reopening"))
                .andExpect(jsonPath("$.data.rejectionReason").value("first decision"));

        mockMvc.perform(patch("/api/v1/projects/{id}/submit-for-review", firstId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/projects/{id}/reject", firstId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejection_reason\":\"second decision\"}"))
                .andExpect(status().isOk());

        String nextResponse = mockMvc.perform(post("/api/v1/projects/{id}/new-version", firstId)
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.name").value("Version Recovery Project"))
                .andReturn().getResponse().getContentAsString();
        UUID secondId = UUID.fromString(objectMapper.readTree(nextResponse).path("data").path("id").asText());

        Project first = projectRepository.findById(firstId).orElseThrow();
        Project second = projectRepository.findById(secondId).orElseThrow();
        assertEquals(Project.ProjectStatus.REJECTED, first.getStatus());
        assertEquals("second decision", first.getRejectionReason());
        assertFalse(first.getIsLatestVersion());
        assertTrue(second.getIsLatestVersion());
        assertEquals(first.getId(), second.getPreviousProjectId());

        // A rejected version is historical and cannot be deleted as if it were a draft.
        mockMvc.perform(delete("/api/v1/projects/{id}", firstId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isUnprocessableEntity());

        // Deleting the unsubmitted successor restores the rejected version as the latest record.
        mockMvc.perform(delete("/api/v1/projects/{id}", secondId)
                        .header("Authorization", "Bearer " + entryToken))
                .andExpect(status().isOk());
        Project restored = projectRepository.findById(firstId).orElseThrow();
        assertTrue(restored.getIsLatestVersion());
        assertEquals(Project.ProjectStatus.REJECTED, restored.getStatus());
        assertEquals("second decision", restored.getRejectionReason());
        assertTrue(projectRepository.findById(secondId).isEmpty());
    }

    @Test
    void pdfAndZipUploadDownloadPreserveBytesMimeTypesAndArabicFilenames() throws Exception {
        String entryToken = login(dataEntryUsername);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String createResponse = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"%s",
                                  "projectNumber":"WF-FILE-%s",
                                  "name":"PDF and ZIP roundtrip",
                                  "year":2026,
                                  "dataCompletionStatus":"COMPLETE"
                                }
                                """.formatted(organizationId, suffix)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).path("data").path("id").asText());

        FileCategory category = new FileCategory();
        category.setCode("WF-FILE-" + suffix);
        category.setName("Workflow file test");
        category.setIsActive(true);
        UUID categoryId = fileCategoryRepository.saveAndFlush(category).getId();

        byte[] pdf = validPdf();
        byte[] zip = zip("folder/مرفق.txt", "archive-content-" + suffix);
        assertFileRoundTrip(entryToken, projectId, categoryId, "تقرير-تجريبي.pdf",
                "application/pdf", pdf);
        assertFileRoundTrip(entryToken, projectId, categoryId, "مرفقات-تجريبية.zip",
                "application/zip", zip);

        byte[] fakeZip = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03};
        assertUploadRejected(entryToken, projectId, categoryId,
                new MockMultipartFile("file", "broken.zip", "application/zip", fakeZip));
        assertUploadRejected(entryToken, projectId, categoryId,
                new MockMultipartFile("file", "spoof.pdf", "application/pdf", zip));
    }

    private void assertFileRoundTrip(String token, UUID projectId, UUID categoryId,
                                     String filename, String mimeType, byte[] original) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename,
                "application/octet-stream", original);
        MockMultipartFile metadata = new MockMultipartFile("metadata", "",
                "application/json",
                ("{\"categoryId\":\"" + categoryId + "\",\"categoryIds\":[\"" + categoryId + "\"]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String uploaded = mockMvc.perform(multipart("/api/v1/projects/{id}/files/upload", projectId)
                        .file(file).file(metadata)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID fileId = UUID.fromString(objectMapper.readTree(uploaded).path("data").path("id").asText());

        // StreamingResponseBody is completed asynchronously. Dispatch the async result before
        // inspecting the response body; reading the initial MockMvc response can observe an
        // empty body even though the download endpoint returned HTTP 200.
        var pendingDownload = mockMvc.perform(get("/api/v1/files/{id}/download", fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();

        var downloaded = mockMvc.perform(asyncDispatch(pendingDownload))
                .andExpect(status().isOk())
                .andExpect(content().contentType(mimeType))
                .andReturn();
        byte[] returned = downloaded.getResponse().getContentAsByteArray();
        assertArrayEquals(original, returned, "Downloaded bytes must exactly match the uploaded " + filename);
        assertEquals(hash(original), hash(returned), "SHA-256 must survive upload, MinIO storage and HTTP download");
        if ("application/pdf".equals(mimeType)) {
            try (PDDocument parsed = Loader.loadPDF(returned)) {
                assertEquals(1, parsed.getNumberOfPages(), "The downloaded PDF must remain parseable");
            }
        }
        String disposition = downloaded.getResponse().getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.toLowerCase().contains("filename"),
                "Content-Disposition must retain the original UTF-8 filename");
    }

    private void assertUploadRejected(String token, UUID projectId, UUID categoryId,
                                      MockMultipartFile file) throws Exception {
        MockMultipartFile metadata = new MockMultipartFile("metadata", "",
                "application/json",
                ("{\"categoryId\":\"" + categoryId + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/projects/{id}/files/upload", projectId)
                        .file(file).file(metadata)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private static byte[] validPdf() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(bytes);
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(String entryName, String contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry(entryName));
            archive.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String login(String username) throws Exception {
        String body = """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("token").asText();
    }
}
