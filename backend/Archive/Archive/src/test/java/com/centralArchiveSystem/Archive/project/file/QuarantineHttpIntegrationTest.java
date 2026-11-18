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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
