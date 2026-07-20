package com.airsense.api.controllers;

import com.airsense.api.dto.LoginRequest;
import com.airsense.api.dto.LoginResponse;
import com.airsense.api.dto.UserDto;
import com.airsense.api.services.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getMe(Authentication authentication) {
        String userId = authentication.getName(); // Since our UserPrincipal returns email in getUsername but we use subject=id, wait, UserPrincipal getUsername returns email, but JwtAuthenticationFilter uses UserDetails.getUsername which is email. Let's fix that.
        // Actually JwtAuthenticationFilter sets UserDetails. So authentication.getName() returns email. We can cast to UserPrincipal to get ID.
        com.airsense.api.security.UserPrincipal principal = (com.airsense.api.security.UserPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(authService.getMe(principal.getId()));
    }
}
