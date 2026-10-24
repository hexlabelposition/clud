package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import dev.clud.server.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "clud.registration-mode=open")
@AutoConfigureMockMvc
class RegistrationControllerTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void createAdmin() {
		clearData();
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, created_at)
				VALUES (?, 'admin', 'encoded-password', 'ADMIN', CURRENT_TIMESTAMP)
				""", UUID.randomUUID());
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void createsUserWithEncodedPasswordAndAllowsLogin() throws Exception {
		var csrfResponse = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
		var token = json.readTree(csrfResponse.getContentAsString());
		var anonymousCookie = csrfResponse.getCookie("SESSION");
		var response = mvc.perform(post("/api/v1/auth/register").cookie(anonymousCookie)
				.header(token.get("headerName").asString(), token.get("token").asString())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"Alice","email":"Alice@Example.COM","password":"  Пароль-AbC-123  ","role":"ADMIN","quotaBytes":1}
				""")).andExpect(status().isCreated()).andReturn().getResponse();
		assertThat(response.getContentAsString()).isEmpty();
		assertThat(response.getCookie("SESSION")).isNull();
		var user = jdbc.queryForMap("SELECT * FROM users WHERE username = 'Alice'");
		assertThat(user.get("role")).isEqualTo("USER");
		assertThat(user.get("email")).isEqualTo("alice@example.com");
		assertThat(user.get("quota_bytes")).isNull();
		assertThat(user.get("disabled")).isEqualTo(false);
		assertThat((String) user.get("password_hash")).startsWith("{bcrypt}");
		assertThat(passwords.matches("  Пароль-AbC-123  ", (String) user.get("password_hash"))).isTrue();
		mvc.perform(get("/api/v1/me").cookie(anonymousCookie)).andExpect(status().isUnauthorized());

		var login = mvc.perform(post("/api/v1/auth/login").cookie(anonymousCookie)
				.header(token.get("headerName").asString(), token.get("token").asString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", "aLiCe", "password", "  Пароль-AbC-123  "))))
				.andExpect(status().isNoContent()).andReturn().getResponse();
		var profile = mvc.perform(get("/api/v1/me").cookie(login.getCookie("SESSION")))
				.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(json.readTree(profile.getContentAsString()).get("role").asString()).isEqualTo("USER");
	}

	@Test
	void reportsOpenModeAnonymously() throws Exception {
		var response = mvc.perform(get("/api/v1/auth/registration")).andExpect(status().isOk()).andReturn().getResponse();
		assertThat(json.readTree(response.getContentAsString()).get("mode").asString()).isEqualTo("OPEN");
		assertThat(response.getCookie("SESSION")).isNull();
	}

	@Test
	void rejectsRegistrationUntilSetupCompletes() throws Exception {
		jdbc.update("DELETE FROM users");
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(request("Alice"))).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
		mvc.perform(post("/api/v1/setup").contentType(MediaType.APPLICATION_JSON).content(request("admin")))
				.andExpect(status().isCreated());
	}

	@Test
	void rejectsDuplicateUsernameIgnoringCase() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(request("ADMIN"))).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@Test
	void requiresValidCsrfAndJson() throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(request("Alice")))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/register").with(csrf().useInvalidToken())
				.contentType(MediaType.APPLICATION_JSON).content(request("Alice"))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("username=Alice&password=password")).andExpect(status().isUnsupportedMediaType());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{", "{\"username\":\"Alice\"}",
			"{\"username\":\"a/b\",\"password\":\"password\"}",
			"{\"username\":\"Alice\",\"password\":\" \"}",
			"{\"username\":\"Alice\",\"password\":\"password\",\"email\":\"invalid\"}" })
	void rejectsInvalidRequests(String body) throws Exception {
		var response = mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isBadRequest()).andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@Test
	void enforcesPasswordUtf8Limit() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", "Alice", "password", "я".repeat(37)))))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", "Alice", "password", "я".repeat(36)))))
				.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT email FROM users WHERE username = 'Alice'", String.class)).isNull();
	}

	@Test
	void concurrentRegistrationsOfSameUsernameCreateOneAccount() throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(2);
			var start = new CountDownLatch(1);
			var requests = List.of("Alice", "ALICE").stream().map(username -> executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content(request(username))).andReturn().getResponse().getStatus();
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(requests.get(0).get(20, TimeUnit.SECONDS), requests.get(1).get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
	}

	private String request(String username) throws Exception {
		return json.writeValueAsString(Map.of("username", username, "password", "password"));
	}

}
