package dev.clud.server.users.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.clud.server.users.AccountCredentials;
import dev.clud.server.users.AccountLookup;

@Service
class AccountLookupService implements AccountLookup {

	private final UserRepository users;

	AccountLookupService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountCredentials> findByUsername(String username) {
		return users.findByUsernameIgnoreCase(username)
				.map(user -> new AccountCredentials(user.getId(), user.getPasswordHash(), user.getRole(), user.isDisabled()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountCredentials> findById(UUID id) {
		return users.findById(id)
				.map(user -> new AccountCredentials(user.getId(), user.getPasswordHash(), user.getRole(), user.isDisabled()));
	}

}
