package com.centralArchiveSystem.Archive.common;

import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unpaginated selection lists, exercised over HTTP.
 *
 * <p>Each of these resources also has a paginated sibling and a {@code /{id}} route, and that is
 * the whole reason this test exists at the HTTP layer rather than in the service tests. A service
 * test cannot tell whether {@code /all} was routed to the selection list or swallowed by the
 * {@code UUID} path variable above it, and the second case is a 400 that arrives only after a
 * client has already been written against the route. A form that received that 400 has no
 * dropdown at all, which is the failure these endpoints exist to remove.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReferenceLookupHttpIntegrationTest {

    private static final String[] SELECTION_ROUTES = {
            "/api/v1/confidentiality-levels/all",
            "/api/v1/organizations/all",
            "/api/v1/roles/all",
            "/api/v1/file-types/all"
    };

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String accessToken;
    private User admin;
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

        admin = new User();
        admin.setUsername("lookup_admin_" + suffix);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Lookup Integration Admin");
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
        assertTrue(!accessToken.isBlank(), "login returned no access token");
    }

    /**
     * The hierarchy goes, because rows left behind outlive the run that made them and the next run
     * then asserts against a table it never reasoned about.
     *
     * <p>The account stays. Logging in writes an audit row that references it, and the audit trail
     * is deliberately not deletable, so removing the account would mean either losing that row or
     * reaching past the application into the table. Every other HTTP integration test in this suite
     * leaves its account behind for the same reason.
     */
    @AfterEach
    void removeTheHierarchyTheTestCreated() {
        for (UUID id : createdOrganizationIds) {
            organizationRepository.deleteById(id);
        }
    }

    @Test
    void everySelectionListAnswersWithABareArrayAndNotThePagedEnvelope() throws Exception {
        for (String route : SELECTION_ROUTES) {
            JsonNode data = getData(route);

            assertTrue(data.isArray(), route + " did not return a bare array, so a client cannot "
                    + "iterate it as a dropdown -- it returned " + data.getNodeType());

            // A client written against the paginated sibling reads data.content[i]. Handing it the
            // flat list instead yields an undefined, silently empty dropdown.
            assertTrue(!data.has("content"), route + " returned the paged envelope, not the flat list");
        }
    }

    @Test
    void theAllSegmentIsNotSwallowedByTheIdRoute() throws Exception {
        // 400 here means the UUID path variable above claimed "all" and failed to convert it,
        // which reads to a client as a broken endpoint rather than as a routing mistake.
        for (String route : SELECTION_ROUTES) {
            mockMvc.perform(get(route).header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void theReferenceDataTheSelectionListsNeedIsPresent() throws Exception {
        // A dropdown that arrives empty is a dropdown that looks like a bug, so the seeded rows
        // are asserted rather than assumed: these tables are populated by the initializer.
        assertTrue(getData("/api/v1/confidentiality-levels/all").size() > 0,
                "no confidentiality levels to offer");
        assertTrue(getData("/api/v1/roles/all").size() > 0, "no roles to offer");
    }

    @Test
    void aSelectionListIsNotServedToAnUnauthenticatedCaller() throws Exception {
        for (String route : SELECTION_ROUTES) {
            mockMvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void narrowingAnOrganizationListReturnsOnlyThatType() throws Exception {
        // A hierarchy the service will accept, because a factory is refused without a company above
        // it and a department is refused without a line above that. Four rows, one of each type, so
        // the assertion does not depend on what the initializer happened to seed.
        String marker = UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = createOrganization(null, "COMPANY", "Lookup Co " + marker);
        UUID factoryId = createOrganization(companyId, "FACTORY", "Lookup Factory " + marker);
        UUID lineId = createOrganization(factoryId, "LINE", "Lookup Line " + marker);
        UUID departmentId = createOrganization(lineId, "DEPARTMENT", "Lookup Dept " + marker);

        JsonNode unfiltered = getData("/api/v1/organizations/all");
        assertTrue(containsId(unfiltered, departmentId),
                "the department just created is missing from the unfiltered list");

        JsonNode departments = getData("/api/v1/organizations/all?type=DEPARTMENT");

        assertTrue(containsId(departments, departmentId), "the type filter dropped its own match");
        for (JsonNode row : departments) {
            assertEquals("DEPARTMENT", row.path("type").asText(),
                    "the type filter let through a " + row.path("type").asText());
        }
        // Narrowing has to actually remove rows, not merely relabel the ones it kept.
        assertTrue(departments.size() < unfiltered.size(),
                "the type filter did not narrow the list at all");
    }

    private UUID createOrganization(UUID parentId, String type, String name) throws Exception {
        String body = """
                {
                  "parentId": %s,
                  "name": "%s",
                  "code": "%s",
                  "type": "%s"
                }
                """.formatted(parentId == null ? "null" : "\"" + parentId + "\"",
                name, name.replace(' ', '-').toUpperCase(), type);

        String response = mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID created = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
        createdOrganizationIds.add(created);
        return created;
    }
    private boolean containsId(JsonNode rows, UUID id) {
        for (JsonNode row : rows) {
            if (id.toString().equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode getData(String route) throws Exception {
        String response = mockMvc.perform(get(route).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }
}
