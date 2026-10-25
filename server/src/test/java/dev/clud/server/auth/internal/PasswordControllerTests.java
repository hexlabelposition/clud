package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.clud.server.TestcontainersConfiguration;
import dev.clud.server.users.PasswordAccounts;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordControllerTests {

	private static final String PASSWORD = "  Пароль-AbC-123  ";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	PasswordEncoder passwords;

	@Autowired
	ObjectMapper json;

	@Autowired
	JdbcIndexedSessionRepository sessions;

	@Autowired
	PasswordAccounts passwordAccounts;

	UUID userId;

	@BeforeEach
	void createUser() {
		clearData();
		userId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, created_at)
				VALUES (?, 'Alice', ?, 'ADMIN', CURRENT_TIMESTAMP)
				""", userId, passwords.encode(PASSWORD));
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void changesPasswordRotatesCurrentSessionAndRevokesOnlyOtherSessions() throws Exception {
		Cookie current = login("aLiCe", PASSWORD);
		Cookie other = login("aLiCe", PASSWORD);
		jdbc.update("INSERT INTO users (id, username, password_hash, role, created_at) VALUES (?, 'Bob', ?, 'USER', CURRENT_TIMESTAMP)",
				UUID.randomUUID(), passwords.encode(PASSWORD));
		Cookie bob = login("Bob", PASSWORD);
		var token = csrf(current);
		String replacement = "  Новый-пароль-456  ";
		var response = changePassword(current, token, passwordBody(PASSWORD, replacement));
		assertThat(response.getStatus()).isEqualTo(204);
		Cookie rotated = response.getCookie("SESSION");
		assertThat(rotated.getValue()).isNotEqualTo(current.getValue());

		String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
		assertThat(hash).startsWith("{bcrypt}");
		assertThat(passwords.matches(replacement, hash)).isTrue();
		assertThat(passwords.matches(PASSWORD, hash)).isFalse();
		assertThat(sessions.findById(sessionId(other))).isNull();
		assertThat(sessions.findById(sessionId(current))).isNull();
		mvc.perform(get("/api/v1/me").cookie(other)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").cookie(current)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").cookie(rotated)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me").cookie(bob)).andExpect(status().isOk());

		var oldLogin = csrf(null);
		mvc.perform(post("/api/v1/auth/login").cookie(oldLogin.cookie()).header(oldLogin.header(), oldLogin.token())
				.contentType(MediaType.APPLICATION_JSON).content(loginBody("aLiCe", PASSWORD)))
				.andExpect(status().isUnauthorized());
		login("aLiCe", replacement);
		// The CSRF token moves with the session to its new ID.
		assertThat(changePassword(rotated, token, passwordBody(replacement, PASSWORD)).getStatus()).isEqualTo(204);
	}

	@Test
	void requiresAuthenticationAndCsrf() throws Exception {
		var anonymous = csrf(null);
		assertProblem(changePassword(anonymous.cookie(), anonymous, passwordBody(PASSWORD, "replacement-password")), 401);
		Cookie cookie = login("aLiCe", PASSWORD);
		mvc.perform(post("/api/v1/auth/password").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content(passwordBody(PASSWORD, "replacement-password"))).andExpect(status().isForbidden());
	}

	@Test
	void invalidChangesLeaveHashAndSessionsIntact() throws Exception {
		Cookie cookie = login("aLiCe", PASSWORD);
		Cookie other = login("aLiCe", PASSWORD);
		var token = csrf(cookie);
		String hash = currentHash();
		for (String body : new String[] { "{}", "{", passwordBody("wrong", "replacement-password"),
				passwordBody(PASSWORD.strip(), "replacement-password"), passwordBody(PASSWORD, ""),
				passwordBody(PASSWORD, " ".repeat(12)), passwordBody(PASSWORD, "a".repeat(73)),
				passwordBody(PASSWORD, "я".repeat(37)), passwordBody("😀".repeat(19), "replacement-password") }) {
			var response = changePassword(cookie, token, body);
			assertProblem(response, 400);
			assertThat(response.getCookie("SESSION")).isNull();
		}
		assertThat(currentHash()).isEqualTo(hash);
		mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me").cookie(other)).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = { "disabled", "deleted" })
	void rejectsUnavailableAccount(String scenario) throws Exception {
		Cookie cookie = login("aLiCe", PASSWORD);
		var token = csrf(cookie);
		makeUnavailable(scenario);
		assertProblem(changePassword(cookie, token, passwordBody(PASSWORD, "replacement-password")), 401);
	}

	@ParameterizedTest
	@ValueSource(strings = { "disabled", "deleted" })
	void rejectsAccountThatBecomesUnavailableWhileHashing(String scenario) throws Exception {
		Cookie cookie = login("aLiCe", PASSWORD);
		Cookie other = login("aLiCe", PASSWORD);
		var token = csrf(cookie);
		duringEncode(() -> makeUnavailable(scenario));
		assertProblem(changePassword(cookie, token, passwordBody(PASSWORD, "replacement-password")), 401);
		assertThat(sessions.findById(sessionId(other))).isNotNull();
	}

	@Test
	void concurrentPasswordChangeConflictsAndKeepsTheWinner() throws Exception {
		Cookie cookie = login("aLiCe", PASSWORD);
		Cookie other = login("aLiCe", PASSWORD);
		var token = csrf(cookie);
		String winner = passwords.encode("winner-password");
		duringEncode(() -> jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", winner, userId));
		var response = changePassword(cookie, token, passwordBody(PASSWORD, "replacement-password"));
		assertProblem(response, 409);
		assertThat(response.getCookie("SESSION")).isNull();
		assertThat(currentHash()).isEqualTo(winner);
		assertThat(sessions.findById(sessionId(other))).isNotNull();
	}

	@Test
	void updateRejectsAHashThatWasAlreadyReplaced() {
		String hash = currentHash();
		String replacement = passwords.encode("replacement-password");
		assertThat(passwordAccounts.replacePassword(userId, hash, replacement)).isTrue();
		assertThat(passwordAccounts.replacePassword(userId, hash, passwords.encode("another-password"))).isFalse();
		assertThat(currentHash()).isEqualTo(replacement);
	}

	private void makeUnavailable(String scenario) {
		if (scenario.equals("disabled")) {
			jdbc.update("UPDATE users SET disabled = true WHERE id = ?", userId);
		}
		else {
			jdbc.update("DELETE FROM users WHERE id = ?", userId);
		}
	}

	/** Simulates a concurrent request that commits after verification but before the update. */
	private void duringEncode(Runnable change) {
		doAnswer(invocation -> {
			Object hash = invocation.callRealMethod();
			change.run();
			return hash;
		}).when(passwords).encode(any(CharSequence.class));
	}

	private String currentHash() {
		return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
	}

	private MockHttpServletResponse changePassword(Cookie cookie, CsrfExchange csrf, String body) throws Exception {
		return mvc.perform(post("/api/v1/auth/password").cookie(cookie).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	private void assertProblem(MockHttpServletResponse response, int status) {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
	}

	private String passwordBody(String current, String replacement) throws Exception {
		return json.writeValueAsString(Map.of("currentPassword", current, "newPassword", replacement));
	}

	private Cookie login(String username, String password) throws Exception {
		var csrf = csrf(null);
		return mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(loginBody(username, password)))
				.andExpect(status().isNoContent()).andReturn().getResponse().getCookie("SESSION");
	}

	private String loginBody(String username, String password) throws Exception {
		return json.writeValueAsString(Map.of("username", username, "password", password));
	}

	private CsrfExchange csrf(Cookie cookie) throws Exception {
		var request = get("/api/v1/auth/csrf");
		if (cookie != null) {
			request.cookie(cookie);
		}
		MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
		var body = json.readTree(result.getResponse().getContentAsString());
		return new CsrfExchange(cookie == null ? result.getResponse().getCookie("SESSION") : cookie,
				body.get("headerName").asString(), body.get("token").asString());
	}

	private String sessionId(Cookie cookie) {
		return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
	}

	record CsrfExchange(Cookie cookie, String header, String token) {
	}

}
