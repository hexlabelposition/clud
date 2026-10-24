package dev.clud.server.users.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.clud.server.users.RegistrationAccounts;
import dev.clud.server.users.Role;

@Service
class RegistrationAccountsService implements RegistrationAccounts {

	private final UserRepository users;

	private final JdbcTemplate jdbc;

	RegistrationAccountsService(UserRepository users, JdbcTemplate jdbc) {
		this.users = users;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public Result createUser(String username, String email, String passwordHash) {
		// Share setup's lock: registration must not become the first account, and
		// competing registrations must check username uniqueness atomically.
		jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
		if (users.count() == 0) {
			return Result.SETUP_REQUIRED;
		}
		if (users.findByUsernameIgnoreCase(username).isPresent()) {
			return Result.USERNAME_TAKEN;
		}
		users.saveAndFlush(new User(username, email, passwordHash, Role.USER, null));
		return Result.CREATED;
	}

}
