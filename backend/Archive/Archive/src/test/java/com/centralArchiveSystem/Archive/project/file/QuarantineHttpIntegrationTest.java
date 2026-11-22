package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.JwtTokenProvider;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuarantineHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;
    @Autowired MinioService minio;
    @Autowired com.centralArchiveSystem.Archive.config.MinioConfig minioConfig;

    private String token(String role) {
        User user = new User();
        user.setUsername("quarantine_" + UUID.randomUUID().toString().substring(0,8));
        user.setFullName("Quarantine test");
        user.setPasswordHash("unused");
        user.setRoles(Set.of(roles.findByCode(role).orElseThrow()));
        users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername()).password("unused").roles(role).build());
    }

    @Test
    void quarantineIsVisibleOnlyToAdminInventoryAndNeverOrdinaryFileRoutes() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID();
        UUID quarantine = UUID.randomUUID(), active = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                organization, "Quarantine Org", organization.toString());
        jdbc.update("""
            INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year)
            VALUES (?,?,?,?,?,2026)
            """, project, UUID.randomUUID(), organization, "Q-1", "Quarantine project");
        jdbc.update("""
            INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,
               object_key,status,source_type,quarantine_reason,size_bytes)
            VALUES (?,?,'danger.exe','danger.bin','archive-files-ci',
                    'legacy/migration/quarantine/test','QUARANTINED','MIGRATION',
                    'Executable or unidentified file extension',7)
            """, quarantine, project);
        jdbc.update("""
            INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,
               object_key,status,source_type,size_bytes)
            VALUES (?,?,'normal.txt','normal.txt','archive-files-ci',
                    'legacy/migration/files/test','ACTIVE','MIGRATION',5)
            """, active, project);

        String admin = "Bearer " + token("system_admin");
        String reader = "Bearer " + token("read_only");

        // The full integration suite can preserve other quarantined fixtures.
        // Verify this entry by its own ID rather than relying on a global count
        // or assuming it sorts to the first row.
        String matching = "$.data.data[?(@.id == '" + quarantine + "')]";
        mvc.perform(get("/api/v1/admin/quarantine").header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath(matching).isNotEmpty())
            .andExpect(jsonPath(matching + ".quarantineReason").exists())
            .andExpect(jsonPath("$.data.data[*].objectKey").doesNotExist());
        mvc.perform(get("/api/v1/admin/quarantine/{id}", quarantine)
                    .header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(quarantine.toString()));
        mvc.perform(get("/api/v1/admin/quarantine").header("Authorization", reader))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/quarantine/{id}/download", quarantine)
                    .header("Authorization", reader))
            .andExpect(status().isForbidden());

        // A reviewer may document a keep decision, without changing the file bytes or status.
        mvc.perform(post("/api/v1/admin/quarantine/{id}/review", quarantine)
                .header("Authorization", reader)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"KEEP\",\"note\":\"Manual review is required\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/quarantine/{id}/review", quarantine)
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"KEEP\",\"note\":\"Executable signature requires isolation\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fileStatus").value("QUARANTINED"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM project.quarantine_reviews WHERE file_id=?",
                Integer.class, quarantine));
        // An unsafe executable cannot be released, even by an administrator.
        mvc.perform(post("/api/v1/admin/quarantine/{id}/review", quarantine)
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RELEASE\",\"note\":\"Attempt release without validation\"}"))
            .andExpect(status().is4xxClientError());
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "SELECT status FROM project.files WHERE id=?", String.class, quarantine));

        // Even an administrator cannot accidentally access quarantined bytes
        // through the normal metadata, preview, or download API.
        mvc.perform(get("/api/v1/files/{id}", quarantine)
                    .header("Authorization", admin)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/files/{id}/preview", quarantine)
                    .header("Authorization", admin)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/files/{id}/download", quarantine)
                    .header("Authorization", admin)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/files/search").param("fileName", "danger")
                    .header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/files/search").param("fileName", "normal")
                    .header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1));
    }
    @Test
    void verifiedPdfReleasePreservesMinioBytesAndReviewAudit() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID(), fileId = UUID.randomUUID();
        byte[] bytes = "%PDF-1.4\\n1 0 obj\\n<< /Type /Catalog >>\\nendobj\\n%%EOF\\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String checksum = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String bucket = minioConfig.getBucket();
        String objectKey = "ci/quarantine-review/" + UUID.randomUUID() + "/report.pdf.bin";
        minio.uploadRaw(new java.io.ByteArrayInputStream(bytes), bucket, objectKey,
                "application/octet-stream", bytes.length);
        try {
            jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                    organization, "Release Test Org", organization.toString());
            jdbc.update("""
                INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year)
                VALUES (?,?,?,?,?,2026)
                """, project, UUID.randomUUID(), organization, "Q-VALID", "Verified document");
            jdbc.update("""
                INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,
                    object_key,status,source_type,quarantine_reason,size_bytes,checksum_sha256,mime_type)
                VALUES (?,?,?,?,? ,?,'QUARANTINED','MIGRATION','Historical type mismatch',?,?,?)
                """, fileId, project, "report.pdf", "report.pdf.bin", bucket,
                    objectKey, bytes.length, checksum, "application/octet-stream");
            String admin = "Bearer " + token("system_admin");
            String reader = "Bearer " + token("read_only");

            mvc.perform(post("/api/v1/admin/quarantine/{id}/review", fileId)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"RELEASE\",\"note\":\"Verified legacy PDF and checksum\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fileStatus").value("ACTIVE"));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT status FROM project.files WHERE id=?", String.class, fileId));
            assertEquals("Historical type mismatch", jdbc.queryForObject(
                    "SELECT quarantine_reason FROM project.files WHERE id=?", String.class, fileId));
            assertEquals("application/pdf", jdbc.queryForObject(
                    "SELECT mime_type FROM project.files WHERE id=?", String.class, fileId));
            try (var input = minio.downloadFile(bucket, objectKey)) {
                assertArrayEquals(bytes, input.readAllBytes());
            }
            mvc.perform(get("/api/v1/files/{id}", fileId)
                            .header("Authorization", reader))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/admin/quarantine/{id}/reviews", fileId)
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].decision").value("RELEASE"));
            mvc.perform(get("/api/v1/admin/quarantine/{id}/reviews", fileId)
                            .header("Authorization", reader))
                    .andExpect(status().isForbidden());
        } finally {
            minio.deleteFile(bucket, objectKey);
        }
    }

    @Test
    void mismatchedChecksumBlocksReleaseAndDoesNotCreateDecisionRow() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID(), fileId = UUID.randomUUID();
        byte[] bytes = "%PDF-1.4\\n%%EOF\\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String bucket = minioConfig.getBucket();
        String objectKey = "ci/quarantine-review/" + UUID.randomUUID() + "/corrupt.pdf.bin";
        minio.uploadRaw(new java.io.ByteArrayInputStream(bytes), bucket, objectKey,
                "application/octet-stream", bytes.length);
        try {
            jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                    organization, "Checksum Org", organization.toString());
            jdbc.update("""
                INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year)
                VALUES (?,?,?,?,?,2026)
                """, project, UUID.randomUUID(), organization, "Q-MISMATCH", "Failed integrity");
            jdbc.update("""
                INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,
                    object_key,status,source_type,quarantine_reason,size_bytes,checksum_sha256)
                VALUES (?,?,?,?,?,?,'QUARANTINED','MIGRATION','Checksum mismatch',?,?)
                """, fileId, project, "corrupt.pdf", "corrupt.pdf.bin", bucket,
                    objectKey, bytes.length, "0".repeat(64));
            String admin = "Bearer " + token("system_admin");
            mvc.perform(post("/api/v1/admin/quarantine/{id}/review", fileId)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"RELEASE\",\"note\":\"Forensic recheck of stored file\"}"))
                    .andExpect(status().is4xxClientError());
            assertEquals("QUARANTINED", jdbc.queryForObject(
                    "SELECT status FROM project.files WHERE id=?", String.class, fileId));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM project.quarantine_reviews WHERE file_id=?",
                    Integer.class, fileId));
        } finally {
            minio.deleteFile(bucket, objectKey);
        }
    }

}
