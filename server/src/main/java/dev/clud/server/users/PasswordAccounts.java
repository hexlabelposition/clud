package dev.clud.server.users;

import java.util.UUID;

/** Password persistence boundary for the auth module. */
public interface PasswordAccounts {

	/** Replaces only the verified hash of an enabled account. */
	boolean replacePassword(UUID id, String expectedHash, String newHash);

}
