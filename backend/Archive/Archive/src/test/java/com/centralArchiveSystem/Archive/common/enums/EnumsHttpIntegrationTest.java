package com.centralArchiveSystem.Archive.common.enums;

import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceRepository;
import com.centralArchiveSystem.Archive.import_.migration.MigrationSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog and the migration-source write are exercised together because they answer one
 * question: does a value the API refuses stop where it is written?
 *
 * <p>{@link EnumCatalogTest} proves the response matches the enums in process. This proves the two
 * things that only exist at the edge, over real HTTP as an authenticated system admin, because a
 * caller without a session never reaches the content check at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnumsHttpIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired MigrationSourceRepository sourceRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminUsername;
    private String accessToken;
    private final String password = "StrongIntegrationPassword123!";

    @BeforeEach
    void loginAsAdmin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUsername = "enums_integration_admin_" + suffix;

        Role adminRole = roleRepository.findByCode("system_admin").orElseGet(() -> {
            Role created = new Role();
            created.setCode("system_admin");
            created.setName("System Admin");
            created.setIsActive(true);
            return roleRepository.save(created);
        });

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Enums Integration Admin");
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(adminRole));
        userRepository.save(admin);

        String loginJson = """
                {"username":"%s","password":"%s"}
                """.formatted(adminUsername, password);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        accessToken = data.path("token").asText();
        assertTrue(!accessToken.isBlank(), "login returned no access token");
    }

    @Test
    void theCatalogIsNotServedToAnUnauthenticatedCaller() throws Exception {
        // Reference data, not a public description of the schema.
        mockMvc.perform(get("/api/v1/enums"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCatalogIsReadableByAnAuthenticatedUser() throws Exception {
        String response = mockMvc.perform(get("/api/v1/enums")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode values = objectMapper.readTree(response).path("data");
        for (String key : new String[]{"projectStatus", "userStatus", "organizationType",
                "migrationSourceType", "migrationBatchCompletionStatus", "projectSortFields"}) {
            assertTrue(values.has(key), key + " is missing from the response");
            assertTrue(values.get(key).isArray() && !values.get(key).isEmpty(),
                    key + " is empty in the response");
        }
    }

    @Test
    void anUnsupportedSourceTypeIsRefusedOnWrite() throws Exception {
        // ORACLE was the example the old MigrationSourceRequest documented, and that DTO took any
        // string, so the row was stored and only refused when a batch was run against it -- after
        // the migration window had been opened on a source nobody could run.
        String body = """
                {
                  "name": "Oracle source",
                  "sourceType": "ORACLE",
                  "databaseName": "legacy",
                  "officeName": "Oracle Office %s"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/migration-sources")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertTrue(sourceRepository.findByNameContainingIgnoreCase("Oracle source").isEmpty(),
                "the refused source type reached the table");
    }

    @Test
    void aSupportedSourceTypeIsStored() throws Exception {
        String office = "Enums Office " + UUID.randomUUID();
        String body = """
                {
                  "name": "Enums source",
                  "sourceType": "POSTGRESQL",
                  "officeName": "%s",
                  "description": "created by the enums integration test"
                }
                """.formatted(office);

        String response = mockMvc.perform(post("/api/v1/migration-sources")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The response reports the enum name, so the client renders the same string it selected.
        assertEquals(MigrationSourceType.POSTGRESQL.name(),
                objectMapper.readTree(response).path("data").path("sourceType").asText());
    }
}
