package com.centralArchiveSystem.Archive.common;

import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Arabic text and field-level error contracts across HTTP, JSON, JDBC and PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class ArabicDataAndFieldErrorsHttpIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;
    private String accessToken;
    private final List<UUID> createdProjectIds = new ArrayList<>();
    private final List<UUID> createdOrganizationIds = new ArrayList<>();
    private final String password = "StrongIntegrationPassword123!";

    @BeforeEach
    void loginAsAdmin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Role role = roleRepository.findByCode("system_admin").orElseGet(() -> {
            Role created = new Role(); created.setCode("system_admin");
            created.setName("System Admin"); created.setIsActive(true);
            return roleRepository.save(created);
        });
        User admin = new User(); admin.setUsername("arabic_admin_" + suffix);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Arabic Integration Admin"); admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(role)); userRepository.save(admin);
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(admin.getUsername(), password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        accessToken = objectMapper.readTree(response).path("data").path("token").asText();
        assertFalse(accessToken.isBlank());
    }

    /** Keep accounts referenced by immutable audit records; remove children before parents. */
    @AfterEach
    void removeWhatTheTestCreated() {
        createdProjectIds.forEach(projectRepository::deleteById);
        createdOrganizationIds.forEach(organizationRepository::deleteById);
    }

    @Test
    void arabicIsStoredAndReadBackUnchanged() throws Exception {
        UUID id = createOrganization("وزارة الخارجية", "و-خ-001", "COMPANY");
        String response = mockMvc.perform(get("/api/v1/organizations/" + id)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        assertEquals("وزارة الخارجية", data.path("name").asText());
        assertEquals("و-خ-001", data.path("code").asText());
    }

    @Test
    void aDuplicateArabicCodeNamesTheFieldAndQuotesTheValue() throws Exception {
        String code = "و-خ-دوب";
        createOrganization("وزارة الأولى", code, "COMPANY");
        JsonNode error = postExpectingError("{\"name\":\"وزارة ثانية\",\"code\":\"" + code
                + "\",\"type\":\"COMPANY\"}", 409, "/api/v1/organizations");
        assertTrue(error.path("message").asText().contains("رمز المؤسسة"));
        assertTrue(error.path("message").asText().contains(code));
        JsonNode detail = error.path("details").get(0);
        assertNotNull(detail); assertEquals("code", detail.path("field").asText());
    }

    @Test
    void aRejectedOrganizationTypeListsTheAllowedValuesAndLeaksNoClassName() throws Exception {
        JsonNode error = postExpectingError("{\"name\":\"Bad Type\",\"code\":\"BT-1\",\"type\":\"MINISTRY\"}",
                400, "/api/v1/organizations");
        String message = error.path("message").asText();
        assertTrue(message.contains("COMPANY")); assertTrue(message.contains("DEPARTMENT"));
        assertFalse(message.contains("centralArchiveSystem")); assertFalse(message.contains("OrganizationType"));
    }

    @Test
    void aSingleBlankFieldBecomesTheHeadlineAsWellAsTheDetail() throws Exception {
        JsonNode error = postExpectingError("{\"name\":\"\",\"code\":\"BLANK-NAME\",\"type\":\"COMPANY\"}",
                400, "/api/v1/organizations");
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        JsonNode detail = error.path("details").get(0);
        assertEquals("name", detail.path("field").asText());
        assertEquals("حقل اسم المؤسسة مطلوب", detail.path("message").asText());
        assertEquals(detail.path("message").asText(), error.path("message").asText());
    }

    @Test
    void anUnreadableValueNamesTheFieldItStoppedAt() throws Exception {
        JsonNode error = postExpectingError("{\"name\":\"Bad Parent\",\"code\":\"BP-1\",\"type\":\"COMPANY\","
                + "\"parentId\":\"not-a-uuid\"}", 400, "/api/v1/organizations");
        assertNotNull(error.path("details").get(0));
        assertEquals("parentId", error.path("details").get(0).path("field").asText());
    }

    /** A 401 from the filter chain must not regress to a container-specific or empty response. */
    @Test
    void anUnauthorizedResponseCarriesAnArabicErrorEnvelope() throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"No Token\",\"code\":\"NT-1\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        JsonNode envelope = objectMapper.readTree(body);
        assertFalse(envelope.path("success").asBoolean());
        assertEquals("UNAUTHORIZED", envelope.path("data").path("code").asText());
        assertEquals("يرجى تسجيل الدخول للمتابعة", envelope.path("data").path("message").asText());
    }

    @Test
    void aValueInsideAListIsReportedWithItsIndex() throws Exception {
        JsonNode error = postExpectingError("{\"projectIds\":[\"%s\",\"not-a-uuid\"]}".formatted(UUID.randomUUID()),
                400, "/api/v1/export/packages");
        assertNotNull(error.path("details").get(0));
        assertEquals("projectIds[1]", error.path("details").get(0).path("field").asText());
    }

    @Test
    void aProjectNameOverTheColumnWidthIsRefusedAsTooLongAndNotAsAConflict() throws Exception {
        String body = "{\"organizationId\":\"%s\",\"projectNumber\":\"PRJ-LONG-1\",\"name\":\"%s\",\"year\":2026}"
                .formatted(UUID.randomUUID(), "م".repeat(251));
        JsonNode error = postExpectingError(body, 400, "/api/v1/projects");
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        JsonNode detail = error.path("details").get(0);
        assertEquals("name", detail.path("field").asText());
        assertTrue(detail.path("message").asText().contains("250"));
    }

    @Test
    void aProjectNameAtTheColumnWidthIsAccepted() throws Exception {
        UUID organization = createOrganization("شركة الحد الأقصى", "MAXW-" + UUID.randomUUID().toString().substring(0, 6), "COMPANY");
        String name = "م".repeat(250);
        String body = "{\"organizationId\":\"%s\",\"projectNumber\":\"PRJ-MAX-1\",\"name\":\"%s\",\"year\":2026}"
                .formatted(organization, name);
        String response = mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        createdProjectIds.add(UUID.fromString(data.path("id").asText()));
        assertEquals(name, data.path("name").asText());
    }

    private UUID createOrganization(String name, String code, String type) throws Exception {
        String response = mockMvc.perform(post("/api/v1/organizations").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"code\":\"%s\",\"type\":\"%s\"}".formatted(name, code, type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
        createdOrganizationIds.add(id); return id;
    }

    private JsonNode postExpectingError(String body, int expectedStatus, String route) throws Exception {
        String response = mockMvc.perform(post(route).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }
}
