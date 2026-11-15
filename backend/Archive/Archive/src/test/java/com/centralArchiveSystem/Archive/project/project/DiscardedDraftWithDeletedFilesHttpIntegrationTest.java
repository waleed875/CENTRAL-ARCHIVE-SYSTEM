package com.centralArchiveSystem.Archive.project.project;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verify the actual PostgreSQL retention and uniqueness constraints:
 * soft-deleted files must survive draft removal without keeping the draft visible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiscardedDraftWithDeletedFilesHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;

    private String adminToken() {
        User user = new User();
        user.setUsername("discard_" + UUID.randomUUID().toString().substring(0, 8));
        user.setFullName("Draft discard fixture");
        user.setPasswordHash("unused-test-hash");
        user.setRoles(Set.of(roles.findByCode("system_admin").orElseThrow()));
        users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername()).password("unused")
                .roles("system_admin").build());
    }

    @Test
    void removesDraftWithTwoDeletedFilesWithoutDeletingTheirRows() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID();
        UUID fileA = UUID.randomUUID(), fileB = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                organization, "Fixture Org", organization.toString());
        jdbc.update("""
                INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year,
                                             status,source_type)
                VALUES (?,?,?,?,?,2026,'DRAFT','MANUAL')
                """, project, UUID.randomUUID(), organization, "TMP-RETENTION", "Retained Draft");
        for (UUID id : new UUID[]{fileA, fileB}) {
            jdbc.update("""
                    INSERT INTO project.files(id,project_id,original_name,stored_name,
                                              bucket_name,object_key,status,deleted_at)
                    VALUES (?,?,'deleted.svg','deleted.svg','test',?,'DELETED',now())
                    """, id, project, "fixtures/" + id);
        }
        String token = adminToken();
        mvc.perform(delete("/api/v1/projects/{id}", project)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project.files WHERE project_id=?", Long.class, project));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM project.projects
                WHERE id=? AND discarded_at IS NOT NULL AND is_latest_version=false
                """, Long.class, project));
        mvc.perform(get("/api/v1/projects/{id}", project)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/search").param("searchTerm", "TMP-RETENTION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // Soft-discarded drafts no longer reserve project numbers for new work.
        jdbc.update("""
                INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year,
                                             status,source_type)
                VALUES (?,?,?,?,?,2026,'DRAFT','MANUAL')
                """, UUID.randomUUID(), UUID.randomUUID(), organization,
                "TMP-RETENTION", "Replacement Draft");
    }

    @Test
    void activeFileBlocksDraftDeletionAndKeepsTheProjectVisible() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                organization, "Fixture Org", organization.toString());
        jdbc.update("""
                INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year)
                VALUES (?,?,?,?,?,2026)
                """, project, UUID.randomUUID(), organization, "TMP-ACTIVE", "Active Draft");
        jdbc.update("""
                INSERT INTO project.files(id,project_id,original_name,stored_name,
                                          bucket_name,object_key,status)
                VALUES (?,?, 'active.svg','active.svg','test','fixtures/active','ACTIVE')
                """, UUID.randomUUID(), project);
        String token = adminToken();
        mvc.perform(delete("/api/v1/projects/{id}", project)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.meta.message").value(org.hamcrest.Matchers.containsString("ملف")));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project.projects WHERE id=? AND discarded_at IS NULL",
                Long.class, project));
    }
}
