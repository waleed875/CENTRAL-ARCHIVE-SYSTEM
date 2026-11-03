package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.auth.JwtTokenProvider;
import com.centralArchiveSystem.Archive.core.role.Role;
import com.centralArchiveSystem.Archive.core.role.RoleRepository;
import com.centralArchiveSystem.Archive.core.user.User;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unlike MockMvc, this exercises the embedded Tomcat servlet container's real
 * REQUEST -> ASYNC redispatch as a real HTTP client downloads an export.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportStreamingRealServerIntegrationTest {

    @LocalServerPort int port;

    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;

    @MockitoBean ExportService exportService;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void realTomcatCompletesAuthenticatedStreamingDownloadWithoutAsyncAccessDenied() throws Exception {
        UUID packageId = UUID.randomUUID();
        byte[] expected = "export-stream-through-real-tomcat".getBytes(StandardCharsets.UTF_8);
        ProjectExport pkg = new ProjectExport();
        pkg.setId(packageId);
        pkg.setPackageName("export-test.pkg");
        pkg.setStatus("EXPORTED");
        when(exportService.getById(packageId)).thenReturn(pkg);
        when(exportService.downloadPackage(packageId))
                .thenAnswer(ignored -> new ByteArrayInputStream(expected));

        String authorized = tokenFor("export_officer");
        HttpResponse<byte[]> ok = send(packageId, authorized);
        assertEquals(200, ok.statusCode(), new String(ok.body(), StandardCharsets.UTF_8));
        assertArrayEquals(expected, ok.body(), "The ASYNC dispatch must not truncate the downloaded archive");
        assertTrue(ok.headers().firstValue("Content-Disposition").orElse("")
                .contains("export-test.pkg"));

        HttpResponse<byte[]> noToken = send(packageId, null);
        assertEquals(401, noToken.statusCode(), "Unauthenticated requests must still be rejected");

        HttpResponse<byte[]> readOnly = send(packageId, tokenFor("read_only"));
        assertEquals(403, readOnly.statusCode(), "A read-only user must not download exports");
    }

    private HttpResponse<byte[]> send(UUID id, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/export/packages/" + id + "/download"))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String tokenFor(String roleCode) {
        Role role = roles.findByCode(roleCode).orElseThrow();
        String username = "real_stream_" + UUID.randomUUID().toString().substring(0, 12);
        User user = new User();
        user.setUsername(username);
        user.setFullName("Real streaming integration fixture");
        user.setPasswordHash("unused-test-hash");
        user.setStatus(User.UserStatus.ACTIVE);
        user.setRoles(Set.of(role));
        users.saveAndFlush(user);
        return tokens.generateToken(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("unused")
                .roles(roleCode)
                .build());
    }
}
