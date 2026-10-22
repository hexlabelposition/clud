package dev.clud.server.users.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import dev.clud.server.TestcontainersConfiguration;
import dev.clud.server.users.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.ConstraintViolationException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class UserRepositoryTests {

	@Autowired
	UserRepository users;

	@PersistenceContext
	EntityManager entityManager;

	@Autowired
	JdbcTemplate jdbc;

	@ParameterizedTest
	@EnumSource(Role.class)
	void persistsUserWithUuidV7AndRole(Role role) {
		User saved = users.saveAndFlush(new User("Alice", "alice@example.com", "encoded-password", role, 1024L));
		entityManager.clear();

		User loaded = users.findById(saved.getId()).orElseThrow();
		assertThat(loaded.getId().version()).isEqualTo(7);
		assertThat(loaded.getUsername()).isEqualTo("Alice");
		assertThat(loaded.getEmail()).isEqualTo("alice@example.com");
		assertThat(loaded.getPasswordHash()).isEqualTo("encoded-password");
		assertThat(loaded.getRole()).isEqualTo(role);
		assertThat(loaded.getQuotaBytes()).isEqualTo(1024L);
		assertThat(loaded.isDisabled()).isFalse();
		assertThat(loaded.getCreatedAt()).isNotNull();
		assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, loaded.getId()))
				.isEqualTo(role.name());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = { 0, Long.MAX_VALUE })
	void preservesQuotaIncludingUnlimitedAndZero(Long quotaBytes) {
		User saved = users.saveAndFlush(new User("alice", null, "encoded-password", Role.USER, quotaBytes));
		entityManager.clear();

		User loaded = users.findById(saved.getId()).orElseThrow();
		assertThat(loaded.getQuotaBytes()).isEqualTo(quotaBytes);
		assertThat(loaded.getEmail()).isNull();
	}

	@Test
	void findsUsernameIgnoringCase() {
		User saved = users.saveAndFlush(new User("Alice", null, "encoded-password", Role.USER, null));
		entityManager.clear();

		assertThat(users.findByUsernameIgnoreCase("ALICE").orElseThrow().getId()).isEqualTo(saved.getId());
		assertThat(users.findByUsernameIgnoreCase("missing")).isEmpty();
	}

	@Test
	void rejectsUsernameTakenWithDifferentCase() {
		users.saveAndFlush(new User("Alice", null, "encoded-password", Role.USER, null));

		assertThatThrownBy(() -> users.saveAndFlush(new User("alice", null, "encoded-password", Role.USER, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsNegativeQuotaInEntity() {
		assertThatThrownBy(() -> users.saveAndFlush(new User("alice", null, "encoded-password", Role.USER, -1L)))
				.isInstanceOf(ConstraintViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t", "alice ", "al/ice", "al ice", "алиса", "ας" })
	void rejectsInvalidUsername(String username) {
		assertThatThrownBy(() -> users.saveAndFlush(new User(username, null, "encoded-password", Role.USER, null)))
				.isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void acceptsUsernameWithAllowedPunctuation() {
		User saved = users.saveAndFlush(new User("Alice.B_c-1", null, "encoded-password", Role.USER, null));

		assertThat(saved.getUsername()).isEqualTo("Alice.B_c-1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "  Alice@Example.COM ", "alice@example.com" })
	void normalizesEmail(String email) {
		User saved = users.saveAndFlush(new User("alice", email, "encoded-password", Role.USER, null));
		entityManager.clear();

		assertThat(users.findById(saved.getId()).orElseThrow().getEmail()).isEqualTo("alice@example.com");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  " })
	void storesBlankEmailAsNull(String email) {
		User saved = users.saveAndFlush(new User("alice", email, "encoded-password", Role.USER, null));

		assertThat(saved.getEmail()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "alice ", "al/ice", "алиса" })
	void databaseRejectsInvalidUsername(String username) {
		assertThatThrownBy(() -> insertUser(username, "encoded-password", "USER", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_username_format");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t" })
	void databaseRejectsBlankPasswordHash(String passwordHash) {
		assertThatThrownBy(() -> insertUser("alice", passwordHash, "USER", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_password_hash_not_blank");
	}

	@Test
	void databaseRejectsUsernameTakenWithDifferentCase() {
		insertUser("Alice", "encoded-password", "USER", null);

		assertThatThrownBy(() -> insertUser("ALICE", "encoded-password", "USER", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_username_unique");
	}

	@Test
	void databaseRejectsNegativeQuota() {
		assertThatThrownBy(() -> insertUser("alice", "encoded-password", "USER", -1L))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_quota_non_negative");
	}

	@Test
	void databaseRejectsUnknownRole() {
		assertThatThrownBy(() -> insertUser("alice", "encoded-password", "OWNER", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_role_valid");
	}

	private void insertUser(String username, String passwordHash, String role, Long quotaBytes) {
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, quota_bytes, created_at)
				VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), username, passwordHash, role, quotaBytes);
	}

}
