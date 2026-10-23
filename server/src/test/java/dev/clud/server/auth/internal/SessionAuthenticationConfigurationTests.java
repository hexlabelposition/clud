package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import dev.clud.server.users.AccountCredentials;
import dev.clud.server.users.Role;

class SessionAuthenticationConfigurationTests {

	private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	private final AtomicInteger matches = new AtomicInteger();

	private final PasswordEncoder passwords = new PasswordEncoder() {

		@Override
		public String encode(CharSequence rawPassword) {
			return delegate.encode(rawPassword);
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			matches.incrementAndGet();
			return delegate.matches(rawPassword, encodedPassword);
		}

	};

	private final AuthenticationManager authentication = new SessionAuthenticationConfiguration()
			.authenticationManager(username -> Optional.of(new AccountCredentials(UUID.randomUUID(),
					delegate.encode("password"), Role.USER, true)), passwords);

	@Test
	void disabledAccountIsReportedOnlyAfterPasswordCheck() {
		assertThatThrownBy(() -> authenticate("password")).isInstanceOf(DisabledException.class);
		assertThat(matches).hasValue(1);
	}

	@Test
	void disabledAccountWithWrongPasswordFailsAsBadCredentials() {
		assertThatThrownBy(() -> authenticate("wrong")).isInstanceOf(BadCredentialsException.class);
		assertThat(matches).hasValue(1);
	}

	private void authenticate(String password) {
		authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", password));
	}

}
