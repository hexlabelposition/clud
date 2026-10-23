package dev.clud.server.users;

import java.util.Optional;

public interface AccountLookup {

	Optional<AccountCredentials> findByUsername(String username);

}
