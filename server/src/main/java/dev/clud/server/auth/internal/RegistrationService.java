package dev.clud.server.auth.internal;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.clud.server.users.RegistrationAccounts;

@Service
class RegistrationService {

	private final RegistrationAccounts accounts;

	private final PasswordEncoder passwords;

	private final InvitesService invites;

	private final TransactionTemplate transactions;

	RegistrationService(RegistrationAccounts accounts, PasswordEncoder passwords, InvitesService invites,
			TransactionTemplate transactions) {
		this.accounts = accounts;
		this.passwords = passwords;
		this.invites = invites;
		this.transactions = transactions;
	}

	RegistrationAccounts.Status register(String username, String email, String password, String inviteToken) {
		// Hash outside the transaction so bcrypt does not hold a pooled connection or
		// the invite row lock. Malformed tokens are refused before paying for it.
		if (inviteToken != null) {
			InvitesService.requireWellFormed(inviteToken);
		}
		var passwordHash = passwords.encode(password);
		return transactions.execute(status -> {
			var invite = inviteToken == null ? null : invites.lockValid(inviteToken);
			var result = accounts.createUser(username, email, passwordHash);
			if (result.status() == RegistrationAccounts.Status.CREATED && invite != null) {
				invites.use(invite, result.userId());
			}
			return result.status();
		});
	}

}
