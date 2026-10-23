package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import dev.clud.server.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SetupControllerTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	@AfterEach
	void clearUsers() {
		jdbc.update("DELETE FROM users");
	}

	@Test
	void createsAdminAndCompletesSetupWithoutStartingSession() throws Exception {
		assertSetupNeeded(true);

		var result = mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
				.content(request("Alice", "  Пароль-AbC-123  ")).exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT username FROM users", String.class)).isEqualTo("Alice");
		assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("alice@example.com");
		assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("ADMIN");
		assertThat(jdbc.queryForObject("SELECT quota_bytes FROM users", Long.class)).isNull();
		assertThat(jdbc.queryForObject("SELECT disabled FROM users", Boolean.class)).isFalse();
		String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
		assertThat(hash).startsWith("{bcrypt}");
		assertThat(passwords.matches("  Пароль-AbC-123  ", hash)).isTrue();
		assertSetupNeeded(false);

		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
				.content(request("Bob", "other-password")))
				.hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hash);
	}

	@Test
	void anyExistingAccountClosesSetupEvenIfDisabledAndNotAdmin() throws Exception {
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, disabled, created_at)
				VALUES (?, 'existing', 'encoded-password', 'USER', true, CURRENT_TIMESTAMP)
				""", UUID.randomUUID());

		assertSetupNeeded(false);
		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
				.content(request("Alice", "password"))).hasStatus(HttpStatus.CONFLICT);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "alice smith", "alice/", "алиса" })
	void rejectsInvalidUsername(String username) throws Exception {
		assertInvalid(request(username, "password"));
	}

	@Test
	void rejectsOverlongUsernameAndEmail() throws Exception {
		assertInvalid(request("a".repeat(65), "password"));
		assertInvalid(json.writeValueAsString(new SetupController.SetupRequest("Alice",
				"a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63), "password")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "\t" })
	void rejectsBlankPassword(String password) throws Exception {
		assertInvalid(request("Alice", password));
	}

	@ParameterizedTest
	@ValueSource(strings = { "a", "я", "😀" })
	void enforcesPasswordLimitInUtf8Bytes(String unit) throws Exception {
		int bytes = unit.getBytes(StandardCharsets.UTF_8).length;
		String password = unit.repeat(72 / bytes);
		assertInvalid(request("Alice", password + unit));
		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
				.content(request("Alice", password))).hasStatus(HttpStatus.CREATED);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"username\":\"Alice\"}",
			"{\"username\":\"Alice\",\"password\":\"password\",\"email\":\"invalid\"}", "{" })
	void rejectsInvalidOrMissingFields(String body) {
		assertInvalid(body);
	}

	@Test
	void acceptsOptionalEmailAndIgnoresClientSuppliedRoleAndQuota() {
		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"admin","password":"password","role":"USER","quotaBytes":1}
				""")).hasStatus(HttpStatus.CREATED);
		assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isNull();
		assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("ADMIN");
		assertThat(jdbc.queryForObject("SELECT quota_bytes FROM users", Long.class)).isNull();
	}

	@Test
	void rejectsFormSubmissionAndKeepsCsrfForOtherEndpoints() {
		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("username=admin&password=password")).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(mvc.post().uri("/api/v1/other")).hasStatus(HttpStatus.FORBIDDEN);
		assertSetupNeeded(true);
	}

	@Test
	void concurrentRequestsCreateExactlyOneAdmin() throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(2);
			var start = new CountDownLatch(1);
			var requests = List.of("Alice", "Bob").stream().map(username -> executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
						.content(request(username, "password")).exchange().getResponse().getStatus();
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(requests.get(0).get(20, TimeUnit.SECONDS), requests.get(1).get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("ADMIN");
	}

	private String request(String username, String password) throws Exception {
		return json.writeValueAsString(new SetupController.SetupRequest(username, "Alice@Example.COM", password));
	}

	private void assertSetupNeeded(boolean expected) {
		assertThat(mvc.get().uri("/api/v1/setup")).hasStatus(HttpStatus.OK)
				.bodyJson().extractingPath("$.setupNeeded").isEqualTo(expected);
	}

	private void assertInvalid(String body) {
		assertThat(mvc.post().uri("/api/v1/setup").contentType(MediaType.APPLICATION_JSON).content(body))
				.hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertSetupNeeded(true);
	}

}
