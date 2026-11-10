package com.gatekeeper.auth;

import com.gatekeeper.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<GateKeeperPrincipal> optionalPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof GateKeeperPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static GateKeeperPrincipal requirePrincipal() {
        return optionalPrincipal()
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
    }
}

