package dev.clud.server.users;

import java.util.UUID;

/** Internal authentication data exposed to the auth module, never to API clients. */
public record AccountCredentials(UUID id, String passwordHash, Role role, boolean disabled) {
}
