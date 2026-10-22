package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(PasswordConfiguration.class)
class PasswordConfigurationTests {

	@Autowired
	PasswordEncoder passwords;

	@Test
	void encodesWithBcryptAndAlgorithmPrefix() {
		String hash = passwords.encode("correct-password");

		assertThat(passwords).isInstanceOf(DelegatingPasswordEncoder.class);
		assertThat(hash).startsWith("{bcrypt}").hasSize(68);
		assertThat(passwords.matches("correct-password", hash)).isTrue();
		assertThat(passwords.matches("wrong-password", hash)).isFalse();
		assertThat(passwords.upgradeEncoding(hash)).isFalse();
	}

	@Test
	void saltsEachHashIndependently() {
		String first = passwords.encode("same-password");
		String second = passwords.encode("same-password");

		assertThat(first).isNotEqualTo(second);
		assertThat(passwords.matches("same-password", first)).isTrue();
		assertThat(passwords.matches("same-password", second)).isTrue();
	}

	@Test
	void preservesPasswordCaseWhitespaceAndUnicode() {
		String password = "  Пароль-AbC-123  ";
		String hash = passwords.encode(password);

		assertThat(passwords.matches(password, hash)).isTrue();
		assertThat(passwords.matches(password.strip(), hash)).isFalse();
		assertThat(passwords.matches("  пароль-abc-123  ", hash)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "plaintext-password", "{unknown}hash" })
	void rejectsHashesWithoutSupportedAlgorithm(String hash) {
		assertThatThrownBy(() -> passwords.matches("plaintext-password", hash))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recognizesBcryptHashThatNeedsStrongerEncoding() {
		String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("correct-password");

		assertThat(passwords.matches("correct-password", hash)).isTrue();
		assertThat(passwords.upgradeEncoding(hash)).isTrue();
	}

	// bcrypt only uses the first 72 bytes, so longer passwords are rejected outright.
	// The limit is in UTF-8 bytes: a Cyrillic letter takes two.
	@ParameterizedTest
	@ValueSource(strings = { "a", "я" })
	void acceptsPasswordUpTo72Bytes(String unit) {
		String password = unit.repeat(72 / unit.getBytes(StandardCharsets.UTF_8).length);

		assertThat(passwords.matches(password, passwords.encode(password))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "a", "я" })
	void rejectsPasswordLongerThan72Bytes(String unit) {
		String password = unit.repeat(72 / unit.getBytes(StandardCharsets.UTF_8).length + 1);

		assertThatThrownBy(() -> passwords.encode(password))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("72 bytes");
	}

}
