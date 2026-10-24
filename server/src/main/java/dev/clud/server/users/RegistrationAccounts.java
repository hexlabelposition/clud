package dev.clud.server.users;

/** Registration operations. Passwords must already be encoded. */
public interface RegistrationAccounts {

	Result createUser(String username, String email, String passwordHash);

	enum Result {
		CREATED, SETUP_REQUIRED, USERNAME_TAKEN
	}

}
