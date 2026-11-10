package com.gatekeeper.auth;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

public record GateKeeperPrincipal(
        UUID userId,
        String email,
        String fullName,
        Set<Role> roles
) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }
}

