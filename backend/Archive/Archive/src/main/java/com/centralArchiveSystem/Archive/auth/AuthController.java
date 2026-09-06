package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.auth.dto.ChangePasswordRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginRequest;
import com.centralArchiveSystem.Archive.auth.dto.LoginResponse;
import com.centralArchiveSystem.Archive.auth.dto.TokenRefreshRequest;
import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Login successful"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        authService.logout();
        return ResponseEntity.ok(ApiResponse.success(null, "Logout successful"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request) {
        LoginResponse response = authService.refreshToken(request.token());
        return ResponseEntity.ok(ApiResponse.success(response, "Token refreshed successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse.UserInfo>> getCurrentUser() {
        LoginResponse.UserInfo userInfo = authService.getCurrentUser();
        return ResponseEntity.ok(ApiResponse.success(userInfo));
    }

    @PatchMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Password changed successfully"));
    }
}
