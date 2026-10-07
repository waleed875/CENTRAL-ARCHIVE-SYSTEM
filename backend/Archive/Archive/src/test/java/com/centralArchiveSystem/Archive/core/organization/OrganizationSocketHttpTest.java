package com.centralArchiveSystem.Archive.core.organization;

import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP requests exercise the running HTTP stack and its JSON mapper. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class OrganizationSocketHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String token;

    @BeforeEach
    void loginFixtureAdministrator() throws Exception {
        String username = "org_socket_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "IsolatedSocketPassword123!";
        User admin = new User();
        admin.setUsername(username);
        admin.setFullName("Organization socket test");
        admin.setPasswordHash(encoder.encode(password));
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(roles.findByCode("system_admin").orElseThrow()));
        users.saveAndFlush(admin);
        token = call("POST", "/api/v1/auth/login", Map.of("username", username, "password", password), 200)
                .path("data").path("token").asText();
    }

    private JsonNode call(String method, String path, Object body, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        }
        HttpResponse<String> response = http.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), () -> method + " " + path + ": " + response.body());
        return response.body().isBlank() ? json.getNodeFactory().nullNode() : json.readTree(response.body());
    }

    private String create(String type, String parent, String field) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "مؤسسة تجربة " + type);
        body.put("code", "OS-" + UUID.randomUUID());
        body.put("type", type);
        if (parent != null) body.put(field, parent);
        JsonNode result = call("POST", "/api/v1/organizations", body, 201).path("data");
        assertEquals(type, result.path("type").asText());
        if (parent != null) assertEquals(parent, result.path("parentId").asText());
        return result.path("id").asText();
    }

    @Test
    void allFourTypesWorkWithBothParentFieldSpellings() throws Exception {
        for (String field : List.of("parentId", "parent_id")) {
            String parent = null;
            for (String type : List.of("COMPANY", "FACTORY", "LINE", "DEPARTMENT")) {
                parent = create(type, parent, field);
            }
            JsonNode path = call("GET", "/api/v1/organizations/" + parent + "/path", null, 200).path("data");
            assertEquals(4, path.size());
            assertEquals("COMPANY", path.get(0).path("type").asText());
            assertEquals("DEPARTMENT", path.get(3).path("type").asText());
        }
    }

    @Test
    void parentAndActiveAliasesActuallyFilterSelectionAndPagedLists() throws Exception {
        String first = create("COMPANY", null, "parentId");
        String other = create("COMPANY", null, "parentId");
        String child = create("FACTORY", first, "parent_id");
        create("FACTORY", other, "parentId");
        JsonNode selected = call("GET", "/api/v1/organizations/all?parent_id=" + first + "&is_active=true", null, 200).path("data");
        assertEquals(1, selected.size());
        assertEquals(child, selected.get(0).path("id").asText());
        JsonNode page = call("GET", "/api/v1/organizations?parent_id=" + first + "&is_active=true&limit=1", null, 200).path("data");
        assertEquals(1, page.path("limit").asInt());
        assertEquals(1, page.path("total").asInt());
        assertEquals(child, page.path("data").get(0).path("id").asText());
    }

    @Test
    void documentedSnakeCaseDeactivationIsNotSilentlyIgnored() throws Exception {
        String parent = create("COMPANY", null, "parentId");
        String child = create("FACTORY", parent, "parentId");
        JsonNode updated = call("PATCH", "/api/v1/organizations/" + child,
                Map.of("is_active", false), 200).path("data");
        assertFalse(updated.path("isActive").asBoolean(true));
        JsonNode inactive = call("GET", "/api/v1/organizations/all?parent_id=" + parent + "&is_active=false", null, 200).path("data");
        assertEquals(1, inactive.size());
        assertEquals(child, inactive.get(0).path("id").asText());
        JsonNode active = call("GET", "/api/v1/organizations/all?parentId=" + parent + "&isActive=true", null, 200).path("data");
        assertTrue(active.isEmpty());
    }

    @Test
    void missingWrongAndInactiveParentsRemainRejected() throws Exception {
        for (String type : List.of("FACTORY", "LINE", "DEPARTMENT")) {
            call("POST", "/api/v1/organizations", Map.of("code", "OS-" + UUID.randomUUID(),
                    "name", "No parent", "type", type), 400);
        }
        String company = create("COMPANY", null, "parentId");
        call("POST", "/api/v1/organizations", Map.of("code", "OS-" + UUID.randomUUID(),
                "name", "Wrong parent", "type", "LINE", "parent_id", company), 400);
        call("DELETE", "/api/v1/organizations/" + company, null, 200);
        call("POST", "/api/v1/organizations", Map.of("code", "OS-" + UUID.randomUUID(),
                "name", "Inactive parent", "type", "FACTORY", "parent_id", company), 400);
    }

    @Test
    void conflictingQueryAliasesAreRejectedInsteadOfGuessing() throws Exception {
        call("GET", "/api/v1/organizations/all?parentId=" + UUID.randomUUID()
                + "&parent_id=" + UUID.randomUUID(), null, 400);
        call("GET", "/api/v1/organizations?isActive=true&is_active=false", null, 400);
    }
}
