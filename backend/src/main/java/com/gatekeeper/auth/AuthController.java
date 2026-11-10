package com.gatekeeper.auth;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.AuthResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request) {
        UUID userId = SecurityUtils.optionalPrincipal()
                .map(GateKeeperPrincipal::userId)
                .orElse(null);
        authService.logout(request, userId);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserProfileResponse> me() {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.getProfile(principal.userId()));
    }
}

