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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GlobalFileSearchHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;

    private String token() {
        User user = new User();
        user.setUsername("globalfile_" + UUID.randomUUID().toString().substring(0, 8));
        user.setFullName("Global file search fixture");
        user.setPasswordHash("unused-test-hash");
        user.setRoles(Set.of(roles.findByCode("read_only").orElseThrow()));
        users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername()).password("unused")
                .roles("read_only").build());
    }

    private UUID org() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",
                id, "Fixture", id.toString());
        return id;
    }

    private UUID project(UUID organization, String number, String name, boolean latest, boolean discarded) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year,
                                         is_latest_version,discarded_at)
            VALUES (?,?,?,?,?,2026,?,CASE WHEN ? THEN now() ELSE NULL END)
            """, id, UUID.randomUUID(), organization, number, name, latest, discarded);
        return id;
    }

    private UUID file(UUID project, String name, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,object_key,
                                      extension,status,deleted_at)
            VALUES (?,?,?,?,'test',?,? ,?,
                    CASE WHEN ? THEN now() ELSE NULL END)
            """, id, project, name, name, "fixtures/" + id,
                name.substring(name.lastIndexOf('.')+1), deleted ? "DELETED" : "ACTIVE", deleted);
        return id;
    }

    @Test
    void filenameSearchNeverMatchesProjectNameAndHidesDeletedAndDiscardedFiles() throws Exception {
        UUID org = org();
        UUID found = project(org, "FIND-2026", "Different project title", true, false);
        UUID filenameMatch = file(found, "alpha-final-2026.pdf", false);
        file(found, "alpha-final-deleted.pdf", true);
        UUID misleading = project(org, "WRONG-2026", "alpha-final-2026.pdf", true, false);
        file(misleading, "not-a-match.txt", false);
        UUID discarded = project(org, "OLD-2026", "Discarded project", false, true);
        file(discarded, "alpha-final-hidden.pdf", false);

        String auth = "Bearer " + token();
        mvc.perform(get("/api/v1/files/search").param("fileName", "final")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.data[0].id").value(filenameMatch.toString()))
            .andExpect(jsonPath("$.data.data[0].projectName").value("Different project title"));

        mvc.perform(get("/api/v1/files/search").param("fileName", "alpha-final-2026.pdf")
                .param("projectNumber", "WRONG-2026")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void escapedFilenameWildcardsAndOlderVersionsRequireExplicitOptIn() throws Exception {
        UUID org = org();
        UUID old = project(org, "OLD-V", "Old version", false, false);
        file(old, "100%_report.pdf", false);
        UUID latest = project(org, "NEW-V", "Current version", true, false);
        file(latest, "100numbers_report.pdf", false);
        String auth = "Bearer " + token();

        mvc.perform(get("/api/v1/files/search").param("fileName", "100%_")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/files/search").param("fileName", "100%_")
                .param("includeVersions", "true").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.data[0].originalName").value("100%_report.pdf"));
        mvc.perform(get("/api/v1/files/search").param("size", "700")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.limit").value(100));
    }

    @Test
    void lowClearanceCannotSeeFileOrProjectNamesAboveItsClearance() throws Exception {
        UUID organization = org();
        UUID classifiedLevel = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.confidentiality_levels(id,code,name,level_order)
                VALUES (?,?,'Classified for search',990)
                """, classifiedLevel, "FILE_SEARCH_" + UUID.randomUUID());

        UUID publicProject = project(organization, "PUBLIC-S", "Public files", true, false);
        file(publicProject, "public-memo.pdf", false);
        UUID confidentialFile = file(publicProject, "secret-file.pdf", false);
        jdbc.update("UPDATE project.files SET confidentiality_level_id=? WHERE id=?",
                classifiedLevel, confidentialFile);

        UUID secretProject = project(organization, "SECRET-S", "Hidden project", true, false);
        file(secretProject, "secret-project-file.pdf", false);
        jdbc.update("UPDATE project.projects SET confidentiality_level_id=? WHERE id=?",
                classifiedLevel, secretProject);

        String auth = "Bearer " + token();
        mvc.perform(get("/api/v1/files/search").param("fileName", "secret")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/files/search")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.data[0].originalName").value("public-memo.pdf"));
    }

    @Test
    void rejectsInvalidExtensionAndRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/files/search")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/files/search").param("extension", "pdf or bad")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().is4xxClientError());
    }
}
