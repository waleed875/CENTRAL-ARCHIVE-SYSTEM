package com.centralArchiveSystem.Archive.dashboard;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises SQL attribution, current/latest version status, role and clearance
 * isolation and anonymous/reader access through the real HTTP security chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DataEntryProductivityHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;

    private User makeUser(String role, User.UserStatus status) {
        User user = new User();
        user.setUsername("entry_report_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-hash");
        user.setFullName("Report fixture " + role);
        user.setStatus(status);
        user.setRoles(Set.of(roles.findByCode(role).orElseThrow()));
        return users.saveAndFlush(user);
    }

    private String bearer(User user, String role) {
        return "Bearer " + tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername()).password("unused").roles(role).build());
    }

    private void project(UUID id, UUID logicalId, int version, UUID creator,
                         UUID organization, String status, String source,
                         boolean latest, UUID clearance) {
        jdbc.update("""
            INSERT INTO project.projects(id,project_uid,version_no,is_latest_version,
              organization_id,project_number,name,year,status,source_type,
              created_by,confidentiality_level_id)
            VALUES (?,?,?,?,? ,?,?,2026,?,?,?,?)
            """, id, logicalId, version, latest, organization,
                "ENTRY-" + UUID.randomUUID().toString().substring(0, 8),
                "Attributed project " + version, status, source, creator, clearance);
    }

    @Test
    void countsEachManualLogicalProjectOnceByItsOriginalCreator() throws Exception {
        User admin = makeUser("system_admin", User.UserStatus.ACTIVE);
        User creator = makeUser("central_data_entry", User.UserStatus.ACTIVE);
        User editor = makeUser("central_data_entry", User.UserStatus.DISABLED);
        User reader = makeUser("read_only", User.UserStatus.ACTIVE);
        UUID org = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                org, "Reporting Office", org.toString());

        UUID logical = UUID.randomUUID();
        project(UUID.randomUUID(),logical,1,creator.getId(),org,"DRAFT","MANUAL",false,null);
        UUID latest = UUID.randomUUID();
        project(latest,logical,2,editor.getId(),org,"APPROVED","MANUAL",true,null);
        project(UUID.randomUUID(),UUID.randomUUID(),1,creator.getId(),org,"DRAFT","MANUAL",true,null);
        project(UUID.randomUUID(),UUID.randomUUID(),1,creator.getId(),org,"IMPORTED","MIGRATION",true,null);
        project(UUID.randomUUID(),UUID.randomUUID(),1,creator.getId(),org,"DRAFT","BRANCH_PACKAGE",true,null);

        // An inaccessible confidential project must not enter any totals.
        UUID highLevel = UUID.randomUUID();
        jdbc.update("INSERT INTO core.confidentiality_levels(id,code,name,level_order) VALUES (?,?,?,?)",
                highLevel, "fixture_" + UUID.randomUUID(), "Restricted", 997);
        project(UUID.randomUUID(),UUID.randomUUID(),1,creator.getId(),org,"ARCHIVED","MANUAL",true,highLevel);
        UUID discarded = UUID.randomUUID();
        project(discarded,UUID.randomUUID(),1,creator.getId(),org,"DRAFT","MANUAL",true,null);
        jdbc.update("UPDATE project.projects SET discarded_at=now(),is_latest_version=false WHERE id=?",discarded);

        String adminToken = bearer(admin,"system_admin");
        mvc.perform(get("/api/v1/admin/data-entry-productivity")
                        .header("Authorization",adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalProjects").value(2))
                .andExpect(jsonPath("$.data.dataEntryUsers").value(2))
                .andExpect(jsonPath("$.data.users[0].userId").value(creator.getId().toString()))
                .andExpect(jsonPath("$.data.users[0].totalProjects").value(2))
                .andExpect(jsonPath("$.data.users[0].approved").value(1))
                .andExpect(jsonPath("$.data.users[0].draftsAndCorrections").value(1))
                .andExpect(jsonPath("$.data.users[1].userId").value(editor.getId().toString()))
                .andExpect(jsonPath("$.data.users[1].totalProjects").value(0));

        mvc.perform(get("/api/v1/admin/data-entry-productivity/{id}/projects",creator.getId())
                        .header("Authorization",adminToken).param("page","1").param("size","20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.data.length()").value(2));

        mvc.perform(get("/api/v1/admin/data-entry-productivity")
                        .header("Authorization",bearer(reader,"read_only")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/data-entry-productivity/{id}/projects",creator.getId())
                        .header("Authorization",bearer(reader,"read_only")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/data-entry-productivity"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/data-entry-productivity/{id}/projects",creator.getId())
                        .header("Authorization",adminToken).param("size","1000"))
                .andExpect(status().isBadRequest());
        assertEquals("APPROVED",jdbc.queryForObject(
                "SELECT status FROM project.projects WHERE id=?",String.class,latest));
    }
}
