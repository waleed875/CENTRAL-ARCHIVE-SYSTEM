package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Filter-level failures bypass ControllerAdvice; preserve the API's JSON contract. */
public final class SecurityErrorWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SecurityErrorWriter() {}

    public static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(response.getOutputStream(),
                ApiResponse.error(message, new ErrorResponse(code, message, null)));
    }
}
