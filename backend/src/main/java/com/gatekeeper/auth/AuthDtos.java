package com.gatekeeper.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            @Size(max = 255)
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
            String password,

            @NotBlank(message = "Full name is required")
            @Size(max = 150, message = "Full name must not exceed 150 characters")
            String fullName,

            Set<Role> roles
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            String email,

            @NotBlank(message = "Password is required")
            String password
    ) {
    }

    public record RefreshRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {
    }

    public record LogoutRequest(
            String refreshToken
    ) {
    }

    public record UserProfileResponse(
            UUID id,
            String email,
            String fullName,
            Set<Role> roles,
            List<UUID> staffEventIds
    ) {
    }

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserProfileResponse user
    ) {
    }
}

