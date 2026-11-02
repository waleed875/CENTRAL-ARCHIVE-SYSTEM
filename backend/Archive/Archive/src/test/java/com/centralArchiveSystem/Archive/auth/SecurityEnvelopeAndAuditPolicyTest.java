package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.audit.AuditAccessPolicy;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.core.confidentiality.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityEnvelopeAndAuditPolicyTest {
    @Test void unauthorizedIsArabicJsonRatherThanAnHtmlContainerError() throws Exception {
        var response = new MockHttpServletResponse();
        SecurityErrorWriter.write(response, 401, "UNAUTHORIZED", "يرجى تسجيل الدخول للمتابعة");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        var body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertFalse(body.path("success").asBoolean());
        assertEquals("UNAUTHORIZED", body.path("data").path("code").asText());
        assertEquals("يرجى تسجيل الدخول للمتابعة", body.path("data").path("message").asText());
    }
    @Test void historicalAuditRequiresTopClearance() {
        var confidentiality = mock(ConfidentialityAccessService.class);
        var levels = mock(ConfidentialityLevelRepository.class);
        var top = new ConfidentialityLevel(); top.setLevelOrder(4);
        when(levels.findAllByOrderByLevelOrderAsc()).thenReturn(List.of(top));
        when(confidentiality.currentMaxLevelOrder()).thenReturn(1, null, 4);
        var policy = new AuditAccessPolicy(confidentiality, levels);
        assertFalse(policy.canReadAudit()); assertFalse(policy.canReadAudit()); assertTrue(policy.canReadAudit());
    }
    @Test void successMessagesAreLocalizedWithoutChangingPayloads() {
        var response = ApiResponse.success("APPROVED", "Project approved");
        assertEquals("APPROVED", response.getData());
        assertEquals("تم اعتماد المشروع", response.getMeta().getMessage());
    }
}
