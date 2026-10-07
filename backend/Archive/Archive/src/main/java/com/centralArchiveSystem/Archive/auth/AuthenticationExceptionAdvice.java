package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import com.centralArchiveSystem.Archive.common.dto.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Authentication failures must not reach the catch-all HTTP 500 handler. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthenticationExceptionAdvice {
    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class,
            AccountStatusException.class})
    public ResponseEntity<ApiResponse<ErrorResponse>> invalidCredentials(RuntimeException ignored) {
        // Identical response for unknown, disabled, locked and wrong-password accounts.
        // Never include the exception text, username, password or account status.
        String message = "بيانات تسجيل الدخول غير صحيحة أو الحساب غير متاح";
        ErrorResponse error = new ErrorResponse("INVALID_CREDENTIALS", message, null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(message, error));
    }
}
