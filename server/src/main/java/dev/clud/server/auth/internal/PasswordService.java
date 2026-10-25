package dev.clud.server.auth.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import dev.clud.server.users.AccountLookup;
import dev.clud.server.users.PasswordAccounts;

@Service
class PasswordService {

	private final AccountLookup accounts;

	private final PasswordAccounts passwordAccounts;

	private final PasswordEncoder passwords;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	PasswordService(AccountLookup accounts, PasswordAccounts passwordAccounts, PasswordEncoder passwords,
			JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.accounts = accounts;
		this.passwordAccounts = passwordAccounts;
		this.passwords = passwords;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	void change(UUID userId, String currentPassword, String newPassword, String currentSessionId) {
		var account = accounts.findById(userId).filter(found -> !found.disabled())
				.orElseThrow(() -> new BadCredentialsException("Account disabled or no longer exists"));
		if (!passwords.matches(currentPassword, account.passwordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
		}
		// Do bcrypt outside a database transaction. Compare-and-set prevents two
		// concurrent changes from reusing the same verified password hash.
		String newHash = passwords.encode(newPassword);
		transactions.executeWithoutResult(status -> {
			if (!passwordAccounts.replacePassword(userId, account.passwordHash(), newHash)) {
				// Report a disabled or deleted account like the check above; only a
				// concurrent password change is a conflict.
				accounts.findById(userId).filter(found -> !found.disabled())
						.orElseThrow(() -> new BadCredentialsException("Account disabled or no longer exists"));
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Password changed concurrently; retry with current credentials");
			}
			// Session revocation and the password update commit together. Spring
			// Session's repository uses separate transactions, so use its table here.
			jdbc.update("DELETE FROM spring_session WHERE principal_name = ? AND session_id <> ?",
					userId.toString(), currentSessionId);
		});
	}

}
