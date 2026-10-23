package dev.clud.server.users;

/** Account operations used by first-run setup. Passwords must already be encoded. */
public interface FirstRunAccounts {

	boolean isSetupNeeded();

	/** Returns false if an account already exists. */
	boolean createAdmin(String username, String email, String passwordHash);

}
