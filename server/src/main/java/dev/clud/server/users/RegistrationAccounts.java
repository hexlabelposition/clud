package dev.clud.server.users;

import java.util.UUID;

/** Registration operations. Passwords must already be encoded. */
public interface RegistrationAccounts {

	Result createUser(String username, String email, String passwordHash);

	/** The created user ID is present only when status is CREATED. */
	record Result(Status status, UUID userId) {
	}

	enum Status {
		CREATED, SETUP_REQUIRED, USERNAME_TAKEN
	}

}
