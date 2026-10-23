package dev.clud.server.users.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.clud.server.users.FirstRunAccounts;
import dev.clud.server.users.Role;

@Service
class FirstRunAccountsService implements FirstRunAccounts {

	private final UserRepository users;

	private final JdbcTemplate jdbc;

	FirstRunAccountsService(UserRepository users, JdbcTemplate jdbc) {
		this.users = users;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isSetupNeeded() {
		return users.count() == 0;
	}

	@Override
	@Transactional
	public boolean createAdmin(String username, String email, String passwordHash) {
		// An empty table has no row to lock. Serialize setup across server instances
		// and other account inserts until the check and creation have committed.
		jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
		if (users.count() != 0) {
			return false;
		}
		users.saveAndFlush(new User(username, email, passwordHash, Role.ADMIN, null));
		return true;
	}

}
