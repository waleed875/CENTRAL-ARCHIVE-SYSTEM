package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthHttpIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String username;
    private String adminUsername;
    private final String password = "StrongIntegrationPassword123!";

    @BeforeEach
    void setupUser() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        username = "auth_integration_user_" + suffix;
        adminUsername = "auth_integration_admin_" + suffix;

        Role role = roleRepository.findByCode("read_only").orElseGet(() -> {
            Role created = new Role();
            created.setCode("read_only");
            created.setName("Read Only");
            created.setIsActive(true);
            return roleRepository.save(created);
        });

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName("Auth Integration User");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        userRepository.save(user);

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
        admin.setFullName("Auth Integration Admin");
        admin.setStatus(User.UserStatus.ACTIVE);
        admin.setRoles(Set.of(adminRole));
        userRepository.save(admin);
    }

    @Test
    void logoutInvalidatesAccessAndRefreshTokens() throws Exception {
        String loginJson = """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(loginResponse).path("data");
        String accessToken = data.path("token").asText();
        String refreshToken = data.path("refreshToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        String refreshJson = """
                {"token":"%s"}
                """.formatted(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson))
                .andExpect(status().isBadRequest());
    }


    @Test
    void methodSecurityEnforcesAdministrativeRoles() throws Exception {
        String readOnlyToken = loginAndGetAccessToken(username);
        String adminToken = loginAndGetAccessToken(adminUsername);

        String code = "HTTP-AUTH-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String organizationJson = """
                {
                  "name":"HTTP Authorization Company",
                  "code":"%s",
                  "type":"COMPANY",
                  "description":"integration authorization test"
                }
                """.formatted(code);

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + readOnlyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(organizationJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(organizationJson))
                .andExpect(status().isCreated());
    }

    private String loginAndGetAccessToken(String loginUsername) throws Exception {
        String loginJson = """
                {"username":"%s","password":"%s"}
                """.formatted(loginUsername, password);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
