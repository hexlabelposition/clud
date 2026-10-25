package dev.clud.server.users.internal;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.clud.server.users.PasswordAccounts;

@Service
class PasswordAccountsService implements PasswordAccounts {

	private final JdbcTemplate jdbc;

	PasswordAccountsService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public boolean replacePassword(UUID id, String expectedHash, String newHash) {
		return jdbc.update("""
				UPDATE users SET password_hash = ?
				WHERE id = ? AND password_hash = ? AND disabled = false
				""", newHash, id, expectedHash) == 1;
	}

}
