package com.centralArchiveSystem.Archive;

import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs against the same disposable PostgreSQL database as the complete CI suite. */
@SpringBootTest
@AutoConfigureMockMvc
class BackendFunctionalHttpRegressionTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ProjectRepository projects;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private static final String PASSWORD = "BackendRegressionPassword123!";
    private User admin;
    private User reader;
    private UUID organization;
    private String token;

    @BeforeEach
    void fixtures() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = user("fa-admin-" + suffix, "system_admin");
        reader = user("fa-reader-" + suffix, "read_only");
        organization = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations (id,code,name,type,is_active) VALUES (?,?,?,'COMPANY',true)",
                organization, "FA-" + suffix, "Functional audit " + suffix);
        token = login(admin.getUsername());
    }

    private User user(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(username);
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(roles.findByCode(role).orElseThrow()));
        return users.saveAndFlush(user);
    }

    private String login(String username) throws Exception {
        String response = http.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String result = json.readTree(response).path("data").path("token").asText();
        assertFalse(result.isBlank());
        return result;
    }

    @Test
    void wrongPasswordAndUnknownUserHaveTheSame401Contract() throws Exception {
        String expectedMessage = null;
        for (String name : new String[]{admin.getUsername(), "missing-" + UUID.randomUUID()}) {
            String response = http.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(java.util.Map.of("username", name, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data.code").value("INVALID_CREDENTIALS"))
                    .andReturn().getResponse().getContentAsString();
            String message = json.readTree(response).path("data").path("message").asText();
            assertFalse(message.contains(name));
            if (expectedMessage != null) assertEquals(expectedMessage, message);
            expectedMessage = message;
        }
    }

    @Test
    void disabledAccountDoesNotLeakAccountStatusOrReturn500() throws Exception {
        reader.setStatus(User.UserStatus.DISABLED);
        users.saveAndFlush(reader);
        http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", reader.getUsername(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void missingCredentialsRemainValidationErrorsNotAuthenticationFailures() throws Exception {
        http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPaginationIsRejectedAcrossControllers() throws Exception {
        String[][] requests = {{"/api/v1/projects", "page"}, {"/api/v1/projects/search", "size"},
                {"/api/v1/users", "size"}, {"/api/v1/roles", "limit"},
                {"/api/v1/dashboard/activity", "limit"}, {"/api/v1/projects/review-queue", "size"}};
        for (String[] request : requests) {
            for (String value : new String[]{"0", "-1", "abc", "2147483648"}) {
                http.perform(get(request[0]).header("Authorization", "Bearer " + token)
                        .param(request[1], value)).andExpect(status().isBadRequest());
            }
        }
    }

    @Test
    void paginationEnvelopeReportsTheActualCap() throws Exception {
        http.perform(get("/api/v1/projects").header("Authorization", "Bearer " + token).param("size", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limit").value(100));
    }

    private Project project(String name, Project.ProjectStatus status, LocalDateTime submitted) {
        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(organization);
        project.setProjectNumber("FA-" + UUID.randomUUID().toString().substring(0, 8));
        project.setName(name);
        project.setYear(2026);
        project.setStatus(status);
        project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
        project.setSourceType(Project.SourceType.MANUAL);
        project.setCreatedBy(admin.getId());
        project.setSubmittedBy(submitted == null ? null : admin.getId());
        project.setSubmittedAt(submitted);
        return projects.saveAndFlush(project);
    }

    @Test
    void queueOrdersSubmissionAgeNotCreationAgeAndPaginatesInDatabase() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Project newer = project("newer submission", Project.ProjectStatus.UNDER_REVIEW, now);
        Project older = project("older submission", Project.ProjectStatus.UNDER_REVIEW, now.minusDays(3));
        Project unknown = project("legacy unknown age", Project.ProjectStatus.UNDER_REVIEW, null);
        project("not in review", Project.ProjectStatus.DRAFT, now.minusDays(10));
        for (int page = 1; page <= 3; page++) {
            UUID expected = page == 1 ? older.getId() : page == 2 ? newer.getId() : unknown.getId();
            http.perform(get("/api/v1/projects/review-queue").header("Authorization", "Bearer " + token)
                    .param("organizationId", organization.toString()).param("size", "1").param("page", String.valueOf(page)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.data[0].id").value(expected.toString()));
        }
    }

    @Test
    void reviewQueueEnforcesRolesAndConfidentialityEvenForAnAdministrator() throws Exception {
        UUID high = jdbc.queryForObject("SELECT id FROM core.confidentiality_levels ORDER BY level_order DESC LIMIT 1", UUID.class);
        Project restricted = project("restricted", Project.ProjectStatus.UNDER_REVIEW, LocalDateTime.now());
        restricted.setConfidentialityLevelId(high);
        projects.saveAndFlush(restricted);
        http.perform(get("/api/v1/projects/review-queue").header("Authorization", "Bearer " + token)
                .param("organizationId", organization.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        http.perform(get("/api/v1/projects/review-queue").header("Authorization", "Bearer " + login(reader.getUsername())))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/projects/review-queue")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankPatchCannotEraseRequiredProjectIdentity() throws Exception {
        Project draft = project("original", Project.ProjectStatus.DRAFT, null);
        for (String body : new String[]{"{\"name\":\"   \"}", "{\"projectNumber\":\"\"}"}) {
            http.perform(patch("/api/v1/projects/{id}", draft.getId()).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertEquals("original", projects.findById(draft.getId()).orElseThrow().getName());
    }

    @Test
    void rejectedIsTerminalAndCannotBeAccidentallyResubmitted() throws Exception {
        Project rejected = project("rejected", Project.ProjectStatus.REJECTED, LocalDateTime.now());
        http.perform(patch("/api/v1/projects/{id}/submit-for-review", rejected.getId())
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnprocessableEntity());
        http.perform(post("/api/v1/projects/{id}/new-version", rejected.getId())
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void migrationForHistoricalRejectionsIsInstalled() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='24' AND success=true", Integer.class));
    }
}
