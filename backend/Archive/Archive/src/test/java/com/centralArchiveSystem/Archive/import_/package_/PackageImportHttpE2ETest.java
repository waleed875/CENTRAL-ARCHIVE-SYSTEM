package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PackageImportHttpE2ETest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired BranchPackageRepository packageRepository;
    @Autowired PackageProjectRepository packageProjectRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FileRepository fileRepository;

    private final String password = "PackageImportE2EPassword123!";
    private String username;
    private UUID targetOrganizationId;

    @BeforeEach
    void setupFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "package_import_" + suffix;

        Role importOfficer = roleRepository.findByCode("import_officer").orElseGet(() -> {
            Role role = new Role();
            role.setCode("import_officer");
            role.setName("Import Officer");
            role.setIsActive(true);
            return roleRepository.save(role);
        });

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName("Package Import E2E");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(importOfficer));
        userRepository.save(user);

        Organization organization = new Organization();
        organization.setCode("PKG-E2E-" + suffix);
        organization.setName("Package E2E Organization " + suffix);
        organization.setType(Organization.OrganizationType.COMPANY);
        organization.setIsActive(true);
        targetOrganizationId = organizationRepository.save(organization).getId();
    }

    @Test
    void receiveVerifyImportAndInspectOperationsEndToEnd() throws Exception {
        String token = login();
        UUID packageUuid = UUID.randomUUID();
        UUID sourceTerminalUuid = UUID.randomUUID();
        UUID sourceOrganizationId = UUID.randomUUID();
        UUID sourceProjectId = UUID.randomUUID();

        byte[] fileContent = "package import e2e content".getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileContent);

        String manifest = """
                {
                  "package_uuid":"%s",
                  "package_name":"package-e2e.pkg",
                  "schema_version":"1.0",
                  "source_terminal_uuid":"%s",
                  "source_organization_id":"%s",
                  "source_username":"branch-e2e",
                  "projects":[{
                    "project_id":"%s",
                    "organization_id":"%s",
                    "project_number":"PKG-E2E-%s",
                    "name":"Package Import E2E Project",
                    "year":2026,
                    "files":[{
                      "project_id":"%s",
                      "original_name":"document.txt",
                      "extension":"txt",
                      "object_key":"files/document.txt",
                      "size_bytes":%d,
                      "checksum_sha256":"%s"
                    }]
                  }]
                }
                """.formatted(
                packageUuid,
                sourceTerminalUuid,
                sourceOrganizationId,
                sourceProjectId,
                sourceOrganizationId,
                UUID.randomUUID().toString().substring(0, 8),
                sourceProjectId,
                fileContent.length,
                fileHash
        );

        byte[] packageBytes = createPackage(manifest, fileContent);
        MockMultipartFile packageFile = new MockMultipartFile(
                "package_file",
                "package-e2e.pkg",
                "application/octet-stream",
                packageBytes
        );

        String receiveResponse = mockMvc.perform(multipart("/api/v1/packages/receive")
                        .file(packageFile)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();

        UUID packageId = UUID.fromString(
                objectMapper.readTree(receiveResponse).path("data").path("id").asText()
        );

        mockMvc.perform(post("/api/v1/packages/{id}/verify", packageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        String importBody = """
                {
                  "organization_mapping":{
                    "%s":"%s"
                  },
                  "confidentiality_level_id":null
                }
                """.formatted(sourceOrganizationId, targetOrganizationId);

        mockMvc.perform(post("/api/v1/packages/{id}/import", packageId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody))
                .andExpect(status().isAccepted());

        JsonNode operations = awaitTerminalImport(packageId, token);

        assertEquals("IMPORTED", operations.path("data").path("status").asText());
        assertEquals(1, operations.path("data").path("projectStats").path("total").asInt());
        assertEquals(1, operations.path("data").path("projectStats").path("imported").asInt());
        assertEquals(0, operations.path("data").path("projectStats").path("failed").asInt());
        assertEquals("SUCCESS",
                operations.path("data").path("importOperations").get(0).path("status").asText());

        BranchPackage persistedPackage = packageRepository.findById(packageId).orElseThrow();
        assertEquals(BranchPackage.PackageStatus.IMPORTED, persistedPackage.getStatus());
        assertNotNull(persistedPackage.getImportedAt());

        var links = packageProjectRepository.findByPackageId(packageId);
        assertEquals(1, links.size());

        UUID centralProjectId = links.get(0).getProjectId();
        var project = projectRepository.findById(centralProjectId).orElseThrow();
        assertEquals(targetOrganizationId, project.getOrganizationId());
        assertEquals(com.centralArchiveSystem.Archive.project.project.Project.SourceType.BRANCH_PACKAGE,
                project.getSourceType());
        assertEquals(packageUuid.toString(), project.getSourceReference());

        var importedFiles = fileRepository.findByProjectIdAndDeletedAtIsNull(centralProjectId);
        assertEquals(1, importedFiles.size());
        assertEquals(fileHash, importedFiles.get(0).getChecksumSha256());
        assertEquals("BRANCH_PACKAGE", importedFiles.get(0).getSourceType());
        assertEquals(packageUuid.toString(), importedFiles.get(0).getSourceReference());
    }

    private JsonNode awaitTerminalImport(UUID packageId, String token) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        JsonNode latest = null;

        while (System.nanoTime() < deadline) {
            String response = mockMvc.perform(get("/api/v1/packages/{id}/operations", packageId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            latest = objectMapper.readTree(response);
            String packageStatus = latest.path("data").path("status").asText();
            JsonNode importOperations = latest.path("data").path("importOperations");
            String operationStatus = importOperations.isArray() && !importOperations.isEmpty()
                    ? importOperations.get(0).path("status").asText()
                    : "";

            if (("IMPORTED".equals(packageStatus) || "FAILED".equals(packageStatus))
                    && ("SUCCESS".equals(operationStatus)
                        || "PARTIAL".equals(operationStatus)
                        || "FAILED".equals(operationStatus))) {
                return latest;
            }

            Thread.sleep(100);
        }

        fail("Package import did not reach a terminal state within 10 seconds: " + latest);
        return latest;
    }

    private String login() throws Exception {
        String body = """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private byte[] createPackage(String manifest, byte[] fileContent) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("files/document.txt"));
            zip.write(fileContent);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }
}
