package com.centralArchiveSystem.Archive.import_.package_;

import com.centralArchiveSystem.Archive.auth.JwtTokenProvider;
import com.centralArchiveSystem.Archive.core.user.*;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.organization.*;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No test-wide transaction: the asynchronous worker must see committed fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
class ClassifiedAsyncImportHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired OrganizationRepository organizations;
    @Autowired ConfidentialityLevelRepository levels;
    @Autowired JwtTokenProvider tokens;
    @Autowired PackageProjectRepository links;
    @Autowired ProjectRepository projects;
    @Autowired FileRepository files;

    @Test void classifiedProjectsAndFilesImportWithTheSubmittingUsersSecurityContext() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID clearance=levels.findAllByOrderByLevelOrderAsc().getLast().getId();
        User user=new User(); user.setUsername("classified_"+suffix); user.setFullName("Classified importer");
        user.setPasswordHash("unused-test-hash"); user.setMaxConfidentialityLevelId(clearance);
        user.setRoles(Set.of(roles.findByCode("import_officer").orElseThrow())); users.saveAndFlush(user);
        String token=tokens.generateToken(org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password("unused").roles("import_officer").build());
        Organization organization=new Organization(); organization.setCode("CLASS-"+suffix);
        organization.setName("Classified import fixture"); organization.setType(Organization.OrganizationType.COMPANY);
        UUID target=organizations.saveAndFlush(organization).getId();
        UUID source=UUID.randomUUID(), sourceProject=UUID.randomUUID();
        byte[] content="classified import fixture".getBytes(StandardCharsets.UTF_8);
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String manifest="""
            {"package_uuid":"%s","package_name":"classified.pkg","schema_version":"1.0",
             "source_terminal_uuid":"%s","source_organization_id":"%s","projects":[{
                "project_id":"%s","organization_id":"%s","project_number":"CLASS-%s",
                "name":"Classified project","year":2026,"files":[{
                  "project_id":"%s","original_name":"classified.txt","extension":"txt",
                  "object_key":"files/classified.txt","size_bytes":%d,"checksum_sha256":"%s"}]}]}
            """.formatted(UUID.randomUUID(),UUID.randomUUID(),source,sourceProject,source,suffix,sourceProject,content.length,checksum);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("files/classified.txt")); zip.write(content); zip.closeEntry();
        }
        String received=mvc.perform(multipart("/api/v1/packages/receive")
                .file(new MockMultipartFile("package_file","classified.pkg","application/octet-stream",output.toByteArray()))
                .header("Authorization","Bearer "+token)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(received).path("data").path("id").asText());
        mvc.perform(post("/api/v1/packages/{id}/verify",id).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(true));
        String request="{\"organization_mapping\":{\"%s\":\"%s\"},\"confidentiality_level_id\":\"%s\"}"
                .formatted(source,target,clearance);
        mvc.perform(post("/api/v1/packages/{id}/import",id).header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isAccepted());
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        String state=""; JsonNode latest=null;
        while (System.nanoTime()<deadline) {
            String body=mvc.perform(get("/api/v1/packages/{id}/operations",id).header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            latest=json.readTree(body).path("data");
            JsonNode operations=latest.path("importOperations");
            state=operations.isArray()&&!operations.isEmpty()?operations.get(0).path("status").asText():"";
            if (Set.of("SUCCESS","PARTIAL","FAILED","INTERRUPTED").contains(state)) break;
            Thread.sleep(100);
        }
        assertEquals("SUCCESS",state,String.valueOf(latest));
        var committed=links.findByPackageId(id); assertEquals(1,committed.size());
        UUID project=committed.getFirst().getProjectId();
        assertEquals(clearance,projects.findById(project).orElseThrow().getConfidentialityLevelId());
        var imported=files.findByProjectIdAndDeletedAtIsNull(project); assertEquals(1,imported.size());
        assertEquals(clearance,imported.getFirst().getConfidentialityLevelId());
        assertEquals(checksum,imported.getFirst().getChecksumSha256());
    }
}
