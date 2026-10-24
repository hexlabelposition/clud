package dev.clud.server.users.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import dev.clud.server.TestcontainersConfiguration;
import dev.clud.server.users.Role;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeControllerTests {

	private static final String PASSWORD = "test-password";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	ObjectMapper json;

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void returnsOnlyCurrentUsersPublicProfile(Role role) throws Exception {
		User user = users.saveAndFlush(new User("Alice", "alice@example.com", passwords.encode(PASSWORD), role, 1024L));
		users.saveAndFlush(new User("Bob", "bob@example.com", passwords.encode(PASSWORD), Role.USER, null));

		var response = mvc.perform(get("/api/v1/me").cookie(login()))
				.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		var body = json.readTree(response.getContentAsString());
		assertThat(body.size()).isEqualTo(6);
		assertThat(body.get("id").asString()).isEqualTo(user.getId().toString());
		assertThat(body.get("username").asString()).isEqualTo("Alice");
		assertThat(body.get("email").asString()).isEqualTo("alice@example.com");
		assertThat(body.get("role").asString()).isEqualTo(role.name());
		assertThat(body.get("quotaBytes").asLong()).isEqualTo(1024L);
		assertThat(Instant.parse(body.get("createdAt").asString())).isEqualTo(user.getCreatedAt());
		assertThat(response.getContentAsString()).doesNotContain("password", PASSWORD, user.getPasswordHash(), "Bob");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = { 0, Long.MAX_VALUE })
	void preservesNullableEmailAndQuotaSemantics(Long quota) throws Exception {
		users.saveAndFlush(new User("Alice", null, passwords.encode(PASSWORD), Role.USER, quota));
		var response = mvc.perform(get("/api/v1/me").cookie(login()))
				.andExpect(status().isOk()).andReturn().getResponse();
		var body = json.readTree(response.getContentAsString());
		assertThat(body.has("email")).isTrue();
		assertThat(body.get("email").isNull()).isTrue();
		assertThat(body.has("quotaBytes")).isTrue();
		if (quota == null) {
			assertThat(body.get("quotaBytes").isNull()).isTrue();
		}
		else {
			assertThat(body.get("quotaBytes").asLong()).isEqualTo(quota);
		}
	}

	@Test
	void readsCurrentProfileInsteadOfSessionSnapshot() throws Exception {
		User user = users.saveAndFlush(new User("Alice", null, passwords.encode(PASSWORD), Role.USER, null));
		Cookie cookie = login();
		jdbc.update("UPDATE users SET username = 'Renamed', email = 'new@example.com', role = 'ADMIN', quota_bytes = 42 WHERE id = ?",
				user.getId());

		var response = mvc.perform(get("/api/v1/me").cookie(cookie))
				.andExpect(status().isOk()).andReturn().getResponse();
		var body = json.readTree(response.getContentAsString());
		assertThat(body.get("username").asString()).isEqualTo("Renamed");
		assertThat(body.get("email").asString()).isEqualTo("new@example.com");
		assertThat(body.get("role").asString()).isEqualTo("ADMIN");
		assertThat(body.get("quotaBytes").asLong()).isEqualTo(42L);
	}

	@Test
	void rejectsAnonymousRequestWithoutCreatingSessionOrRedirecting() throws Exception {
		var response = mvc.perform(get("/api/v1/me"))
				.andExpect(status().isUnauthorized()).andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getCookie("SESSION")).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "DELETE FROM users", "UPDATE users SET disabled = TRUE" })
	void rejectsAndInvalidatesSessionForUnusableAccount(String accountChange) throws Exception {
		users.saveAndFlush(new User("Alice", null, passwords.encode(PASSWORD), Role.USER, null));
		Cookie cookie = login();
		jdbc.update(accountChange);
		var response = mvc.perform(get("/api/v1/me").cookie(cookie))
				.andExpect(status().isUnauthorized()).andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(response.getCookie("SESSION").getMaxAge()).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
	}

	@Test
	void rejectsExpiredSession() throws Exception {
		users.saveAndFlush(new User("Alice", null, passwords.encode(PASSWORD), Role.USER, null));
		Cookie cookie = login();
		jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0");
		mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	private Cookie login() throws Exception {
		var csrfResponse = mvc.perform(get("/api/v1/auth/csrf"))
				.andExpect(status().isOk()).andReturn().getResponse();
		var csrf = json.readTree(csrfResponse.getContentAsString());
		return mvc.perform(post("/api/v1/auth/login").cookie(csrfResponse.getCookie("SESSION"))
				.header(csrf.get("headerName").asString(), csrf.get("token").asString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", "Alice", "password", PASSWORD))))
				.andExpect(status().isNoContent()).andReturn().getResponse().getCookie("SESSION");
	}

}
