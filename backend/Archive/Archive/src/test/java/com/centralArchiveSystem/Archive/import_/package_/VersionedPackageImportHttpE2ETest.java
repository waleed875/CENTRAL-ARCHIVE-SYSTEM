package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.core.organization.Organization;
import com.centralArchiveSystem.Archive.core.organization.OrganizationRepository;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.Project;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VersionedPackageImportHttpE2ETest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired FileRepository files;
    @Autowired PackageProjectRepository packageProjects;

    @Test
    void versionedPackageRestoresWholeProjectFamilyWithSeparateFileSets() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "versioned_pkg_" + suffix;
        String password = "VersionedImportStrongPassword123!";

        Role role = roles.findByCode("import_officer").orElseThrow();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Versioned package test");
        user.setPasswordHash(passwords.encode(password));
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        users.saveAndFlush(user);

        Organization organization = new Organization();
        organization.setCode("V2-ORG-" + suffix);
        organization.setName("Versioned Import " + suffix);
        organization.setType(Organization.OrganizationType.COMPANY);
        organization.setIsActive(true);
        UUID targetOrg = organizations.saveAndFlush(organization).getId();

        String token = login(username, password);
        UUID packageUuid = UUID.randomUUID();
        UUID sourceOrg = UUID.randomUUID();
        UUID familyUid = UUID.randomUUID();
        UUID originalFirst = UUID.randomUUID();
        UUID originalSecond = UUID.randomUUID();

        byte[] v1 = ("archive-version-one-" + suffix).getBytes(StandardCharsets.UTF_8);
        byte[] v2 = ("archive-version-two-" + suffix).getBytes(StandardCharsets.UTF_8);
        String projectNo = "V2-PRJ-" + suffix;
        String manifest = """
                {
                  "package_uuid":"%s",
                  "package_name":"versioned-e2e.pkg",
                  "schema_version":"2.0",
                  "source_terminal_uuid":"%s",
                  "source_organization_id":"%s",
                  "projects":[
                    {
                      "project_id":"%s", "project_uid":"%s", "version_no":1,
                      "is_latest_version":false,
                      "organization_id":"%s","project_number":"%s",
                      "name":"Original Project", "year":2026,
                      "files":[{
                        "project_id":"%s",
                        "original_name":"original.txt", "extension":"txt",
                        "object_key":"files/version-one.txt",
                        "size_bytes":%d,"checksum_sha256":"%s"
                      }]
                    },
                    {
                      "project_id":"%s", "project_uid":"%s", "version_no":2,
                      "previous_project_id":"%s", "is_latest_version":true,
                      "organization_id":"%s","project_number":"%s",
                      "name":"Updated Project", "year":2026,
                      "files":[{
                        "project_id":"%s",
                        "original_name":"updated.txt","extension":"txt",
                        "object_key":"files/version-two.txt",
                        "size_bytes":%d,"checksum_sha256":"%s"
                      }]
                    }
                  ]
                }
                """.formatted(packageUuid, UUID.randomUUID(), sourceOrg,
                originalFirst, familyUid, sourceOrg, projectNo, originalFirst, v1.length, hash(v1),
                originalSecond, familyUid, originalFirst, sourceOrg, projectNo,
                originalSecond, v2.length, hash(v2));

        MockMultipartFile multipart = new MockMultipartFile("package_file", "versioned-e2e.pkg",
                "application/octet-stream", archive(manifest, v1, v2));
        String response = mvc.perform(multipart("/api/v1/packages/receive")
                        .file(multipart).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID packageId = UUID.fromString(json.readTree(response).path("data").path("id").asText());

        mvc.perform(post("/api/v1/packages/{id}/verify", packageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        String mapping = """
                {"organization_mapping":{"%s":"%s"}}
                """.formatted(sourceOrg, targetOrg);
        mvc.perform(post("/api/v1/packages/{id}/import", packageId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapping))
                .andExpect(status().isAccepted());

        JsonNode terminal = awaitTerminal(packageId, token);
        assertEquals("IMPORTED", terminal.path("data").path("status").asText(), terminal.toString());
        assertEquals("SUCCESS",
                terminal.path("data").path("importOperations").get(0).path("status").asText(),
                terminal.toString());
        assertEquals(2, terminal.path("data").path("projectStats").path("imported").asInt());
        List<PackageProject> links = packageProjects.findByPackageId(packageId);
        assertEquals(2, links.size());

        Project importedFirst = projects.findById(links.stream()
                .filter(l -> l.getLocalProjectUuid().equals(originalFirst))
                .findFirst().orElseThrow().getProjectId()).orElseThrow();
        Project importedSecond = projects.findById(links.stream()
                .filter(l -> l.getLocalProjectUuid().equals(originalSecond))
                .findFirst().orElseThrow().getProjectId()).orElseThrow();

        assertNotEquals(importedFirst.getId(), importedSecond.getId());
        assertNotEquals(familyUid, importedFirst.getProjectUid());
        assertEquals(importedFirst.getProjectUid(), importedSecond.getProjectUid());
        assertEquals(1, importedFirst.getVersionNo());
        assertEquals(2, importedSecond.getVersionNo());
        assertFalse(importedFirst.getIsLatestVersion());
        assertTrue(importedSecond.getIsLatestVersion());
        assertNull(importedFirst.getPreviousProjectId());
        assertEquals(importedFirst.getId(), importedSecond.getPreviousProjectId());
        assertEquals(targetOrg, importedFirst.getOrganizationId());
        assertEquals(targetOrg, importedSecond.getOrganizationId());
        assertEquals("IMPORTED", importedFirst.getStatus().name());
        assertEquals("IMPORTED", importedSecond.getStatus().name());
        assertEquals(hash(v1), files.findByProjectIdAndDeletedAtIsNull(importedFirst.getId())
                .get(0).getChecksumSha256());
        assertEquals(hash(v2), files.findByProjectIdAndDeletedAtIsNull(importedSecond.getId())
                .get(0).getChecksumSha256());
    }

    private String login(String username, String password) throws Exception {
        String result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("token").asText();
    }

    private JsonNode awaitTerminal(UUID id, String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            String response = mvc.perform(get("/api/v1/packages/{id}/operations", id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            last = json.readTree(response);
            String status = last.path("data").path("importOperations").path(0).path("status").asText();
            if (Set.of("SUCCESS", "PARTIAL", "FAILED").contains(status)) return last;
            Thread.sleep(100);
        }
        fail("Versioned package import timed out: " + last);
        return last;
    }

    private byte[] archive(String manifest, byte[] v1, byte[] v2) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(zip, "files/version-one.txt", v1);
            put(zip, "files/version-two.txt", v2);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
