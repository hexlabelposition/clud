package dev.clud.server.users;

import java.util.Optional;
import java.util.UUID;

public interface AccountLookup {

	Optional<AccountCredentials> findByUsername(String username);

	Optional<AccountCredentials> findById(UUID id);

}
