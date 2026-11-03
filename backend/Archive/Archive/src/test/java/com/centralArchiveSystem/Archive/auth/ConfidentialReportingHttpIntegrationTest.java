package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.user.*;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.confidentiality.ConfidentialityLevelRepository;
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
class ConfidentialReportingHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ConfidentialityLevelRepository levels;
    @Autowired JwtTokenProvider tokens;

    private String user(String role, UUID level) {
        String name="report_"+UUID.randomUUID().toString().substring(0,8);
        User user=new User(); user.setUsername(name); user.setFullName("Report fixture");
        user.setPasswordHash("unused-test-hash"); user.setMaxConfidentialityLevelId(level);
        user.setRoles(Set.of(roles.findByCode(role).orElseThrow())); users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User.withUsername(name)
                .password("unused").roles(role).build());
    }
    @Test void anAdministratorWithoutClearanceCannotReadClassifiedReportRowsOrAuditHistory() throws Exception {
        UUID organization=UUID.randomUUID(), project=UUID.randomUUID();
        UUID high=levels.findAllByOrderByLevelOrderAsc().getLast().getId();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                organization,"Secret fixture",organization.toString());
        jdbc.update("""
            INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year,
                confidentiality_level_id,data_completion_status)
            VALUES (?,?,?,?,?,2026,?,'INCOMPLETE')
            """,project,UUID.randomUUID(),organization,"SECRET", "Classified report fixture",high);
        String token=user("system_admin",null);
        String response=mvc.perform(get("/api/v1/projects/incomplete-report").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(project.toString())); assertFalse(response.contains("Classified report fixture"));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void migrationConnectionMetadataIsNotVisibleToReadOnlyUsers() throws Exception {
        String token=user("read_only",null);
        mvc.perform(get("/api/v1/migration-sources").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.code").value("ACCESS_DENIED"));
    }
    @Test void arabicLabelsAreAvailableToAuthenticatedReadersWithoutChangingEnumValues() throws Exception {
        String token=user("read_only",null);
        mvc.perform(get("/api/v1/enums/labels").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectStatus.UNDER_REVIEW").value("قيد المراجعة"));
        mvc.perform(get("/api/v1/enums/labels")).andExpect(status().isUnauthorized());
    }
}
