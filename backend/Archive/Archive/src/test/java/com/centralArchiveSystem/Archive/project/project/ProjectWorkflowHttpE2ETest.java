package com.centralArchiveSystem.Archive.project.project;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.member.ProjectMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

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

    private final String password = "WorkflowE2EPassword123!";
    private String dataEntryUsername;
    private String auditorUsername;
    private UUID organizationId;

    @BeforeEach
    void setupFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        dataEntryUsername = "workflow_entry_" + suffix;
        auditorUsername = "workflow_auditor_" + suffix;

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
