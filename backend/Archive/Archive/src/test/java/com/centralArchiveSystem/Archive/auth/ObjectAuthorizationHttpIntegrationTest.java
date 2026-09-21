package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevel;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
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
import com.centralArchiveSystem.Archive.project.filetype.FileType;
import com.centralArchiveSystem.Archive.project.filetype.FileTypeRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
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
class ObjectAuthorizationHttpIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ConfidentialityLevelRepository confidentialityLevelRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FileRepository fileRepository;
    @Autowired FileCategoryRepository fileCategoryRepository;
    @Autowired FileTypeRepository fileTypeRepository;

    private String username;
    private final String password = "ObjectAuthorizationPassword123!";
    private UUID lowProjectId;
    private UUID highProjectId;
    private UUID highFileId;

    @BeforeEach
    void setupFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "object_auth_" + suffix;

        Role readOnly = roleRepository.findByCode("read_only").orElseGet(() -> {
            Role role = new Role();
            role.setCode("read_only");
            role.setName("Read Only");
            role.setIsActive(true);
            return roleRepository.save(role);
        });

        ConfidentialityLevel low = new ConfidentialityLevel();
        low.setCode("HTTP_LOW_" + suffix);
        low.setName("HTTP Low " + suffix);
        low.setLevelOrder(10_000 + Math.abs(suffix.hashCode() % 10_000));
        low = confidentialityLevelRepository.save(low);

        ConfidentialityLevel high = new ConfidentialityLevel();
        high.setCode("HTTP_HIGH_" + suffix);
        high.setName("HTTP High " + suffix);
        high.setLevelOrder(low.getLevelOrder() + 50_000);
        high = confidentialityLevelRepository.save(high);

        Organization organization = new Organization();
        organization.setCode("HTTP-ORG-" + suffix);
        organization.setName("HTTP Object Authorization " + suffix);
        organization.setType(Organization.OrganizationType.COMPANY);
        organization.setIsActive(true);
        organization = organizationRepository.save(organization);

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName("Object Authorization User");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setMaxConfidentialityLevelId(low.getId());
        user.setRoles(Set.of(readOnly));
        userRepository.save(user);

        Project lowProject = project(organization.getId(), low.getId(), "LOW-" + suffix);
        lowProject = projectRepository.save(lowProject);
        lowProjectId = lowProject.getId();

        Project highProject = project(organization.getId(), high.getId(), "HIGH-" + suffix);
        highProject = projectRepository.save(highProject);
        highProjectId = highProject.getId();

        FileCategory category = new FileCategory();
        category.setCode("HTTP-CAT-" + suffix);
        category.setName("HTTP Category " + suffix);
        category.setIsActive(true);
        category = fileCategoryRepository.save(category);

        FileType type = new FileType();
        type.setCategoryId(category.getId());
        type.setCode("HTTP-TYPE-" + suffix);
        type.setName("HTTP Type " + suffix);
        type.setDefaultExtension("txt");
        type.setIsActive(true);
        type = fileTypeRepository.save(type);

        FileEntity highFile = new FileEntity();
        highFile.setProjectId(lowProjectId);
        highFile.setCategoryId(category.getId());
        highFile.setTypeId(type.getId());
        highFile.setConfidentialityLevelId(high.getId());
        highFile.setOriginalName("restricted.txt");
        highFile.setStoredName(UUID.randomUUID() + ".txt");
        highFile.setBucketName("archive-files-ci");
        highFile.setObjectKey("tests/" + suffix + "/restricted.txt");
        highFile.setExtension("txt");
        highFile.setMimeType("text/plain");
        highFile.setSizeBytes(10L);
        highFile.setStatus("ACTIVE");
        highFile = fileRepository.save(highFile);
        highFileId = highFile.getId();
    }

    @Test
    void directObjectAccessAndListsRespectConfidentialityClearance() throws Exception {
        String token = loginAndGetAccessToken();

        mockMvc.perform(get("/api/v1/projects/{id}", lowProjectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{id}", highProjectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/files/{id}", highFileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        String listBody = mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(listBody);
        String serialized = root.toString();
        assertTrue(serialized.contains(lowProjectId.toString()));
        assertFalse(serialized.contains(highProjectId.toString()));
    }

    private Project project(UUID organizationId, UUID confidentialityId, String number) {
        Project project = new Project();
        project.setProjectUid(UUID.randomUUID());
        project.setVersionNo(1);
        project.setIsLatestVersion(true);
        project.setOrganizationId(organizationId);
        project.setConfidentialityLevelId(confidentialityId);
        project.setProjectNumber(number);
        project.setName("Object Authorization " + number);
        project.setYear(2026);
        project.setStatus(Project.ProjectStatus.DRAFT);
        project.setDataCompletionStatus(Project.DataCompletionStatus.COMPLETE);
        project.setSourceType(Project.SourceType.MANUAL);
        return project;
    }

    private String loginAndGetAccessToken() throws Exception {
        String body = """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
