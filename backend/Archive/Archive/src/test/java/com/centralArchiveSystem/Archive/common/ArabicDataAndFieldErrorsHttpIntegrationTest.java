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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Arabic data and field-level errors, exercised over HTTP.
 *
 * <p>This is at the HTTP layer rather than in the handler's unit test for two reasons. First, the
 * whole point of the change is that a person filling in a form is told which box is wrong, and a
 * handler test proves only that the handler formats a string, not that the message reaches the
 * client in Arabic with the field attached. Second, the Arabic-storage guarantee is a claim about
 * four layers at once — the request, the JSON parser, the JDBC driver and the column's collation —
 * and only a real round trip through all of them can confirm it.
 */
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

        Role adminRole = roleRepository.findByCode("system_admin").orElseGet(() -> {
            Role created = new Role();
            created.setCode("system_admin");
            created.setName("System Admin");
            created.setIsActive(true);
            return roleRepository.save(created);
        });

        User admin = new User();
        admin.setUsername("arabic_admin_" + suffix);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Arabic Integration Admin");
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(adminRole));
        userRepository.save(admin);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(admin.getUsername(), password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        accessToken = objectMapper.readTree(response).path("data").path("token").asText();
        assertFalse(accessToken.isBlank(), "login returned no access token");
    }

    /**
     * The account is left behind: logging in writes an audit row that references it, and the audit
     * trail is deliberately not deletable, so removing it would mean either losing that row or
     * reaching past the application into the table. Every other HTTP integration test here does the
     * same.
     *
     * <p>Projects are removed before the organisations they point at. Deleting in the other order
     * fails on {@code projects_organization_id_fkey}, which is the foreign key doing its job, and it
     * fails the teardown rather than the assertion, which is a much worse place to discover a leak.
     */
    @AfterEach
    void removeWhatTheTestCreated() {
        for (UUID id : createdProjectIds) {
            projectRepository.deleteById(id);
        }
        for (UUID id : createdOrganizationIds) {
            organizationRepository.deleteById(id);
        }
    }

    /**
     * The premise of the whole exercise: the data goes in as Arabic and comes back as the same
     * Arabic. A name and a code are both stored, read and compared as text, so a mismatch here would
     * mean every Arabic name in the system was quietly altered on the way in.
     */
    @Test
    void arabicIsStoredAndReadBackUnchanged() throws Exception {
        String name = "وزارة الخارجية";
        String code = "و-خ-001";

        UUID id = createOrganization(name, code, "COMPANY");

        String readBack = mockMvc.perform(get("/api/v1/organizations/" + id)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(readBack).path("data");
        assertEquals(name, data.path("name").asText(), "the Arabic name did not survive storage");
        assertEquals(code, data.path("code").asText(), "the Arabic code did not survive storage");
    }

    /**
     * A duplicate is the mistake people make most often, and it used to arrive as one fixed English
     * sentence with no field in it. Both halves matter: the reader has to learn which of their own
     * entries collided, and the form has to know which box to mark.
     */
    @Test
    void aDuplicateArabicCodeNamesTheFieldAndQuotesTheValue() throws Exception {
        String code = "و-خ-دوب";
        createOrganization("وزارة الأولى", code, "COMPANY");

        JsonNode error = postOrganizationExpectingError(
                "{\"name\":\"وزارة ثانية\",\"code\":\"" + code + "\",\"type\":\"COMPANY\"}", 409);

        String message = error.path("message").asText();
        assertTrue(message.contains("رمز المؤسسة"),
                "the message does not name the field: " + message);
        assertTrue(message.contains(code), "the message does not quote the value: " + message);

        JsonNode detail = error.path("details").get(0);
        assertNotNull(detail, "no field was attached, so a form has nothing to mark: " + error);
        assertEquals("code", detail.path("field").asText());
    }

    /**
     * A value outside the allowed set used to be answered by the JVM with the fully qualified name
     * of an internal Java class. That tells a person filling in a form nothing, and it discloses the
     * package layout besides. The allowed set is the answer they can act on.
     */
    @Test
    void aRejectedOrganizationTypeListsTheAllowedValuesAndLeaksNoClassName() throws Exception {
        JsonNode error = postOrganizationExpectingError(
                "{\"name\":\"Bad Type\",\"code\":\"BT-1\",\"type\":\"MINISTRY\"}", 400);

        String message = error.path("message").asText();
        assertTrue(message.contains("COMPANY"), "the allowed values are missing: " + message);
        assertTrue(message.contains("DEPARTMENT"), "the allowed values are incomplete: " + message);
        assertFalse(message.contains("centralArchiveSystem"),
                "an internal package name reached the client: " + message);
        assertFalse(message.contains("OrganizationType"),
                "an internal Java type name reached the client: " + message);
    }

    /**
     * With one bad field the headline is that field's own sentence, so a single mistake reads as one
     * mistake rather than as a failure plus a list to cross-reference.
     */
    @Test
    void aSingleBlankFieldBecomesTheHeadlineAsWellAsTheDetail() throws Exception {
        JsonNode error = postOrganizationExpectingError(
                "{\"name\":\"\",\"code\":\"BLANK-NAME\",\"type\":\"COMPANY\"}", 400);

        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        JsonNode detail = error.path("details").get(0);
        assertEquals("name", detail.path("field").asText());
        assertEquals("حقل اسم المؤسسة مطلوب", detail.path("message").asText());
        assertEquals(detail.path("message").asText(), error.path("message").asText(),
                "with one bad field the headline should be that field's sentence");
    }

    /**
     * A body Jackson cannot read at the point of a named property. The property name is the entire
     * answer for whoever is filling in the form, and it used to be discarded.
     */
    @Test
    void anUnreadableValueNamesTheFieldItStoppedAt() throws Exception {
        JsonNode error = postOrganizationExpectingError(
                "{\"name\":\"Bad Parent\",\"code\":\"BP-1\",\"type\":\"COMPANY\","
                        + "\"parentId\":\"not-a-uuid\"}", 400);

        JsonNode detail = error.path("details").get(0);
        assertNotNull(detail, "the unreadable field was not reported; the response was " + error);
        assertEquals("parentId", detail.path("field").asText());
    }

    /**
     * A 401 arrives from the security filter chain, which is outside the exception handler's reach:
     * it calls {@code response.sendError}, and that is dispatched to the container's error page, not
     * back through a {@code @RestControllerAdvice}. The consequence is that a client reading errors
     * gets a body with no {@code code} and no {@code message} at all, so a form showing the
     * headline has nothing to show and says nothing about why it failed. This asserts the shape it
     * actually has today, because the shape is the thing worth knowing.
     */
    @Test
    void anUnauthorizedResponseDoesNotCarryTheErrorEnvelope() throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Token\",\"code\":\"NT-1\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        JsonNode envelope = objectMapper.readTree(body.isBlank() ? "{}" : body);
        assertTrue(envelope.path("data").isMissingNode() || envelope.path("data").isNull(),
                "a 401 unexpectedly carried the error envelope: " + body);
    }

    /**
     * A value inside a list. Reporting only the property name would say {@code projectIds} without
     * saying which entry was wrong, and a request of forty ids has no other way for the caller to
     * find the one. The index has to travel with it.
     */
    @Test
    void aValueInsideAListIsReportedWithItsIndex() throws Exception {
        JsonNode error = postExpectingError(
                "{\"projectIds\":[\"%s\",\"not-a-uuid\"]}".formatted(UUID.randomUUID()), 400,
                "/api/v1/export/packages");

        JsonNode detail = error.path("details").get(0);
        assertNotNull(detail, "the unreadable entry was not reported; the response was " + error);
        assertEquals("projectIds[1]", detail.path("field").asText());
    }

    /**
     * A project name one character over the column width. It used to pass validation, hit the
     * column, and come back as a 409 conflict — the same response as a duplicate project number,
     * with nothing to tell the two apart. It must now be refused as what it is.
     */
    @Test
    void aProjectNameOverTheColumnWidthIsRefusedAsTooLongAndNotAsAConflict() throws Exception {
        String tooLong = "م".repeat(251);
        String body = """
                {
                  "organizationId": "%s",
                  "projectNumber": "PRJ-LONG-1",
                  "name": "%s",
                  "year": 2026
                }
                """.formatted(UUID.randomUUID(), tooLong);

        JsonNode error = postExpectingError(body, 400, "/api/v1/projects");

        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        JsonNode detail = error.path("details").get(0);
        assertEquals("name", detail.path("field").asText());
        assertTrue(detail.path("message").asText().contains("250"),
                "the limit was not reported: " + detail.path("message").asText());
    }

    /**
     * A name exactly at the column width is still accepted, so the bound is a bound and not a
     * blanket rejection of long Arabic names. This is the case that makes the first test a real
     * limit: without it, a 250-character rejection would pass for correct.
     */
    @Test
    void aProjectNameAtTheColumnWidthIsAccepted() throws Exception {
        UUID organizationId = createOrganization(
                "شركة الحد الأقصى", "MAXW-" + UUID.randomUUID().toString().substring(0, 6), "COMPANY");

        String atTheLimit = "م".repeat(250);
        String body = """
                {
                  "organizationId": "%s",
                  "projectNumber": "PRJ-MAX-1",
                  "name": "%s",
                  "year": 2026
                }
                """.formatted(organizationId, atTheLimit);

        String response = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        createdProjectIds.add(UUID.fromString(data.path("id").asText()));
        assertEquals(atTheLimit, data.path("name").asText());
    }

    // ---- helpers -------------------------------------------------------------------------

    private UUID createOrganization(String name, String code, String type) throws Exception {
        String response = mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"code\":\"%s\",\"type\":\"%s\"}"
                                .formatted(name, code, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
        createdOrganizationIds.add(id);
        return id;
    }

    private JsonNode postOrganizationExpectingError(String body, int expectedStatus) throws Exception {
        return postExpectingError(body, expectedStatus, "/api/v1/organizations");
    }

    /**
     * The parsed {@code data} object, with the whole envelope kept in the caller for failure
     * messages, so a failing assertion prints what actually came back rather than only that some
     * field was absent.
     */
    private JsonNode postExpectingError(String body, int expectedStatus, String route) throws Exception {
        String response = mockMvc.perform(post(route)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).path("data");
    }
}
