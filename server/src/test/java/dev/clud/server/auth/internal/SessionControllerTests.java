package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.clud.server.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

@Import({ TestcontainersConfiguration.class, SessionControllerTests.ProtectedController.class })
@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTests {

	private static final String PASSWORD = "  Пароль-AbC-123  ";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	ObjectMapper json;

	@Autowired
	JdbcIndexedSessionRepository sessions;

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
	void loginRotatesSessionAndPersistsSanitizedAuthenticationInJdbc() throws Exception {
		var csrf = csrf(null);
		Cookie cookie = login(csrf);
		assertThat(cookie.getValue()).isNotEqualTo(csrf.cookie().getValue());
		assertThat(sessions.findById(sessionId(csrf.cookie()))).isNull();

		Session stored = sessions.findById(sessionId(cookie));
		assertThat(stored).isNotNull();
		assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
		SecurityContext context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		var authentication = context.getAuthentication();
		assertThat(authentication.getName()).isEqualTo(userId.toString());
		assertThat(authentication.getAuthorities()).filteredOn(authority -> authority.getAuthority().startsWith("ROLE_"))
				.extracting("authority").containsExactly("ROLE_ADMIN");
		assertThat(authentication.getCredentials()).isNull();
		assertThat(((UserDetails) authentication.getPrincipal()).getPassword()).isNull();
		assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
				String.class, stored.getId())).isEqualTo(userId.toString());
		assertThat(mvc.perform(get("/api/v1/test/session").cookie(cookie)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString()).isEqualTo(userId.toString());
	}

	@Test
	void logoutDeletesSessionAndClearsCookie() throws Exception {
		Cookie cookie = login(csrf(null));
		var csrf = csrf(cookie);
		var result = mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
				.header(csrf.header(), csrf.token())).andExpect(status().isNoContent()).andReturn();
		assertThat(result.getResponse().getCookie("SESSION").getMaxAge()).isZero();
		assertThat(sessions.findById(sessionId(cookie))).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class)).isZero();
		mvc.perform(get("/api/v1/test/session").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutIsIdempotentWithFreshCsrfToken() throws Exception {
		var csrf = csrf(null);
		mvc.perform(post("/api/v1/auth/logout").cookie(csrf.cookie()).header(csrf.header(), csrf.token()))
				.andExpect(status().isNoContent());
		assertThat(sessions.findById(sessionId(csrf.cookie()))).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "wrong-password", "unknown-user", "disabled-user" })
	void rejectsInvalidCredentialsWithoutSavingAuthentication(String scenario) throws Exception {
		if (scenario.equals("disabled-user")) {
			jdbc.update("UPDATE users SET disabled = true");
		}
		var csrf = csrf(null);
		var result = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
						"username", scenario.equals("unknown-user") ? "missing" : "Alice",
						"password", scenario.equals("wrong-password") ? "wrong" : PASSWORD))))
				.andExpect(status().isUnauthorized()).andReturn();
		assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("Alice", "disabled", "missing", PASSWORD);
		Session stored = sessions.findById(sessionId(csrf.cookie()));
		assertThat(stored.<Object>getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
		mvc.perform(get("/api/v1/test/session").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
	}

	@Test
	void requiresCsrfForLoginAndLogoutAndRotatesTokenAfterLogin() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody()))
				.andExpect(status().isForbidden());
		var oldCsrf = csrf(null);
		Cookie cookie = login(oldCsrf);
		mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header(oldCsrf.header(), oldCsrf.token()))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/test/session").cookie(cookie)).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{", "{\"username\":\"Alice\"}",
			"{\"username\":\"Alice\",\"password\":\" \"}" })
	void rejectsMalformedLogin(String body) throws Exception {
		assertInvalidLogin(body);
	}

	@Test
	void rejectsOverlongPasswordsIncludingUnicode() throws Exception {
		for (String password : new String[] { "a".repeat(73), "я".repeat(37), "😀".repeat(19) }) {
			assertInvalidLogin(json.writeValueAsString(Map.of("username", "Alice", "password", password)));
		}
	}

	@Test
	void rejectsFormLogin() throws Exception {
		var csrf = csrf(null);
		mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("username=Alice&password=password"))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void rejectsExpiredSession() throws Exception {
		Cookie cookie = login(csrf(null));
		jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0");
		mvc.perform(get("/api/v1/test/session").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void unauthorizedRequestsDoNotCreateSessionsOrRedirect() throws Exception {
		var result = mvc.perform(get("/api/v1/test/session")).andExpect(status().isUnauthorized()).andReturn();
		assertThat(result.getResponse().getHeader("Location")).isNull();
		assertThat(result.getResponse().getCookie("SESSION")).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
	}

	private void assertInvalidLogin(String body) throws Exception {
		var csrf = csrf(null);
		var result = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andReturn();
		assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
	}

	private Cookie login(CsrfExchange csrf) throws Exception {
		var result = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(loginBody())).andExpect(status().isNoContent()).andReturn();
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		return result.getResponse().getCookie("SESSION");
	}

	private String loginBody() throws Exception {
		return json.writeValueAsString(Map.of("username", "aLiCe", "password", PASSWORD));
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

	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class ProtectedController {

		@GetMapping("/api/v1/test/session")
		String principal(Principal principal) {
			return principal.getName();
		}

	}

}
