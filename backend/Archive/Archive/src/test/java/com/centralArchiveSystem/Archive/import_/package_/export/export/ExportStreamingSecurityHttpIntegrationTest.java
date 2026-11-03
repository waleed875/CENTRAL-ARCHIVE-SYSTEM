package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.auth.JwtTokenProvider;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression: StreamingResponseBody uses a second ASYNC servlet dispatch.
 * A JWT on the original request must also authorize that dispatch; otherwise
 * AuthorizationFilter rejects it after response bytes have been committed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportStreamingSecurityHttpIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    @MockitoBean ExportService exports;

    @Test
    void authenticatedExportOfficerCanFinishAsyncPackageDownload() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] archive = "package-bytes-for-stream-security".getBytes(StandardCharsets.UTF_8);

        ProjectExport pkg = new ProjectExport();
        pkg.setId(id);
        pkg.setPackageName("archive.pkg");
        pkg.setStatus("EXPORTED");
        when(exports.getById(id)).thenReturn(pkg);
        when(exports.downloadPackage(id)).thenAnswer(invocation -> new ByteArrayInputStream(archive));

        String token = tokenFor("export_officer");

        MvcResult started = mvc.perform(get("/api/v1/export/packages/{id}/download", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("archive.pkg")))
                .andReturn();

        assertArrayEquals(archive, completed.getResponse().getContentAsByteArray());
    }

    @Test
    void downloadWithoutAnAccessTokenStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/export/packages/{id}/download", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadWithReadOnlyRoleStillRequiresExportPermission() throws Exception {
        String token = tokenFor("read_only");
        mvc.perform(get("/api/v1/export/packages/{id}/download", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String tokenFor(String roleCode) {
        Role role = roles.findByCode(roleCode).orElseThrow();
        String username = "stream_" + UUID.randomUUID().toString().substring(0, 12);

        User user = new User();
        user.setUsername(username);
        user.setFullName("Streaming export test user");
        user.setPasswordHash("unused-test-password");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        users.saveAndFlush(user);

        return tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(username).password("unused").roles(roleCode).build());
    }
}
