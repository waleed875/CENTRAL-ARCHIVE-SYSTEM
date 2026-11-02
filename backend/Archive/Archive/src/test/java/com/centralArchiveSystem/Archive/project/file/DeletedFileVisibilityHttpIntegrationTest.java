package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.auth.JwtTokenProvider;
import com.centralArchiveSystem.Archive.core.user.*;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
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
class DeletedFileVisibilityHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;

    private String token(String role) {
        User user=new User(); user.setUsername("file_"+UUID.randomUUID().toString().substring(0,8));
        user.setFullName("File visibility fixture"); user.setPasswordHash("unused-test-hash");
        user.setRoles(Set.of(roles.findByCode(role).orElseThrow())); users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password("unused").roles(role).build());
    }
    @Test void onlyAnAdministratorCanListDeletedFilesAndTheyNeverAppearAsActive() throws Exception {
        UUID org=UUID.randomUUID(), project=UUID.randomUUID(), file=UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",org,"Fixture",org.toString());
        jdbc.update("""
            INSERT INTO project.projects(id,project_uid,organization_id,project_number,name,year)
            VALUES (?,?,?,?,?,2026)
            """,project,UUID.randomUUID(),org,"P","Fixture");
        jdbc.update("""
            INSERT INTO project.files(id,project_id,original_name,stored_name,bucket_name,object_key,status,deleted_at)
            VALUES (?,?,'deleted.txt','deleted.txt','test','fixture/deleted','DELETED',now())
            """,file,project);
        String admin=token("system_admin");
        String deleted=mvc.perform(get("/api/v1/projects/{id}/files",project).param("status","DELETED")
                .header("Authorization","Bearer "+admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(deleted.contains(file.toString()));
        String active=mvc.perform(get("/api/v1/projects/{id}/files",project)
                .header("Authorization","Bearer "+admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(active.contains(file.toString()));
        mvc.perform(get("/api/v1/projects/{id}/files",project).param("status","deleted")
                .header("Authorization","Bearer "+token("read_only"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{id}/files",project).param("size","999")
                .header("Authorization","Bearer "+admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(100));
    }
}
