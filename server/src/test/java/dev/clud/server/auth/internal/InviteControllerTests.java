package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import dev.clud.server.TestcontainersConfiguration;
import dev.clud.server.users.RegistrationAccounts;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InviteControllerTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper json;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	RegistrationService registration;

	@Autowired
	TransactionTemplate transactions;

	UUID adminId;

	@BeforeEach
	void createAdmin() {
		clearData();
		adminId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, created_at)
				VALUES (?, 'admin', ?, 'ADMIN', CURRENT_TIMESTAMP)
				""", adminId, passwords.encode("password"));
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM invites");
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void adminCanInviteUserWhoRegistersAndLogsIn() throws Exception {
		Cookie adminCookie = login("admin");
		var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").cookie(adminCookie))
				.andExpect(status().isOk()).andReturn().getResponse();
		var csrfToken = json.readTree(csrfResponse.getContentAsString());
		var expiresAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
		var response = mvc.perform(post("/api/v1/admin/invites").cookie(adminCookie)
				.header(csrfToken.get("headerName").asString(), csrfToken.get("token").asString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("expiresAt", expiresAt.toString()))))
				.andExpect(status().isCreated()).andReturn().getResponse();
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		var body = json.readTree(response.getContentAsString());
		assertThat(body.size()).isEqualTo(3);
		UUID inviteId = UUID.fromString(body.get("id").asString());
		assertThat(inviteId.version()).isEqualTo(7);
		assertThat(Instant.parse(body.get("expiresAt").asString())).isEqualTo(expiresAt);
		String token = body.get("token").asString();
		assertThat(token).matches("clud_inv_[A-Za-z0-9_-]{43}");
		var stored = jdbc.queryForMap("SELECT * FROM invites WHERE id = ?", inviteId);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		assertThat(stored.get("token_hash")).isEqualTo(hash);
		assertThat(stored.get("created_by")).isEqualTo(adminId);
		assertThat(stored.get("used_by")).isNull();
		assertThat(stored.get("used_at")).isNull();
		assertThat(stored.values()).doesNotContain(token);

		var registered = register("Alice", token);
		assertThat(registered.getStatus()).isEqualTo(201);
		assertThat(registered.getContentAsString()).isEmpty();
		assertThat(registered.getCookie("SESSION")).isNull();
		var account = jdbc.queryForMap("SELECT * FROM users WHERE username = 'Alice'");
		assertThat(account.get("role")).isEqualTo("USER");
		assertThat(account.get("quota_bytes")).isNull();
		assertThat(account.get("email")).isEqualTo("alice@example.com");
		assertThat(passwords.matches("password", (String) account.get("password_hash"))).isTrue();
		assertThat(jdbc.queryForObject("SELECT used_by FROM invites WHERE id = ?", UUID.class, inviteId)).isEqualTo(account.get("id"));
		assertThat(jdbc.queryForObject("SELECT used_at FROM invites WHERE id = ?", Instant.class, inviteId)).isNotNull();
		var profile = mvc.perform(get("/api/v1/me").cookie(login("Alice"))).andExpect(status().isOk()).andReturn().getResponse();
		assertThat(json.readTree(profile.getContentAsString()).get("role").asString()).isEqualTo("USER");
		assertInvalidInvitation(register("Bob", token), token);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
	}

	@Test
	void defaultsToSevenDaysAndGeneratesDistinctTokens() throws Exception {
		var before = Instant.now();
		String first = createInvite();
		String second = createInvite();
		assertThat(first).isNotEqualTo(second);
		var expiry = jdbc.queryForObject("SELECT min(expires_at) FROM invites", Instant.class);
		assertThat(expiry).isBetween(before.plus(Duration.ofDays(7)).minusMillis(1), Instant.now().plus(Duration.ofDays(7)));
	}

	@Test
	void requiresAuthenticationAdminAndCsrf() throws Exception {
		mvc.perform(post("/api/v1/admin/invites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", adminId);
		// Even a stale session that still has ROLE_ADMIN must be refused.
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invites", Integer.class)).isZero();
	}

	@Test
	void rejectsOrdinaryUserWithCurrentUserRole() throws Exception {
		jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", adminId);
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("USER"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void rejectsDisabledAndDeletedAdministrators() throws Exception {
		jdbc.update("UPDATE users SET disabled = true WHERE id = ?", adminId);
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		jdbc.update("DELETE FROM users");
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{", "{\"expiresAt\":\"yesterday\"}", "{\"expiresAt\":\"2000-01-01T00:00:00Z\"}" })
	void rejectsInvalidCreationRequest(String body) throws Exception {
		var response = mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invites", Integer.class)).isZero();
	}

	@Test
	void rejectsExpiryBeyondNinetyDays() throws Exception {
		var tooLate = Instant.now().plus(Duration.ofDays(91));
		mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("expiresAt", tooLate.toString()))))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invites", Integer.class)).isZero();
	}

	@Test
	void deletingCreatorOrRedeemerRemovesTheirInvites() throws Exception {
		String redeemed = createInvite();
		assertThat(register("Alice", redeemed).getStatus()).isEqualTo(201);
		createInvite();
		jdbc.update("DELETE FROM users WHERE username = 'Alice'");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invites", Integer.class)).isEqualTo(1);
		jdbc.update("DELETE FROM users WHERE id = ?", adminId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invites", Integer.class)).isZero();
	}

	@Test
	void rejectsExpiredUnknownAndMalformedTokensWithoutCreatingUsers() throws Exception {
		String expired = createInvite();
		jdbc.update("UPDATE invites SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
		assertInvalidInvitation(register("Expired", expired), expired);
		String unknown = "clud_inv_" + "A".repeat(43);
		assertInvalidInvitation(register("Unknown", unknown), unknown);
		assertInvalidInvitation(register("Malformed", "invalid-token"), "invalid-token");
		assertInvalidInvitation(register("Blank", ""), null);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT used_by FROM invites", UUID.class)).isNull();
	}

	@Test
	void validationAndUsernameConflictsDoNotConsumeInvite() throws Exception {
		String token = createInvite();
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", "Alice", "password", "я".repeat(37), "inviteToken", token))))
				.andExpect(status().isBadRequest());
		assertThat(register("ADMIN", token).getStatus()).isEqualTo(409);
		assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNull();
		assertThat(register("Alice", token).getStatus()).isEqualTo(201);
	}

	@Test
	void accountAndInviteConsumptionRollbackTogether() throws Exception {
		String token = createInvite();
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			assertThat(registration.register("Alice", null, "password", token))
					.isEqualTo(RegistrationAccounts.Status.CREATED);
			throw new IllegalStateException("abort registration");
		})).isInstanceOf(IllegalStateException.class).hasMessage("abort registration");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNull();
		assertThat(register("Alice", token).getStatus()).isEqualTo(201);
	}

	@Test
	void simultaneousRedemptionsCreateExactlyOneUser() throws Exception {
		String token = createInvite();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(2);
			var start = new CountDownLatch(1);
			var requests = List.of("Alice", "Bob").stream().map(username -> executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return register(username, token).getStatus();
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(requests.get(0).get(20, TimeUnit.SECONDS), requests.get(1).get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 403);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT used_by FROM invites", UUID.class)).isNotNull();
	}

	@Test
	void redemptionRequiresCsrf() throws Exception {
		String token = createInvite();
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registrationBody("Alice", token))).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNull();
	}

	@Nested
	@TestPropertySource(properties = "clud.registration-mode=closed")
	class Closed {

		@Test
		void refusesValidInviteWithoutConsumingIt() throws Exception {
			String token = createInvite();
			var response = register("Alice", token);
			assertThat(response.getStatus()).isEqualTo(403);
			assertThat(json.readTree(response.getContentAsString()).get("type").asString())
					.isEqualTo(RegistrationController.REGISTRATION_CLOSED);
			assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNull();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		}

	}

	@Nested
	@TestPropertySource(properties = "clud.registration-mode=open")
	class Open {

		@Test
		void consumesSuppliedInviteAndRefusesItsReuse() throws Exception {
			String token = createInvite();
			assertThat(register("Alice", token).getStatus()).isEqualTo(201);
			assertInvalidInvitation(register("Bob", token), token);
		}

	}

	private String createInvite() throws Exception {
		var response = mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isCreated()).andReturn().getResponse();
		return json.readTree(response.getContentAsString()).get("token").asString();
	}

	private MockHttpServletResponse register(String username, String token) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(registrationBody(username, token))).andReturn().getResponse();
	}

	private String registrationBody(String username, String token) throws Exception {
		return json.writeValueAsString(Map.of("username", username, "password", "password",
				"email", "alice@example.com", "inviteToken", token, "role", "ADMIN", "quotaBytes", 1));
	}

	private void assertInvalidInvitation(MockHttpServletResponse response, String token) throws Exception {
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(json.readTree(response.getContentAsString()).get("type").asString()).isEqualTo(InvitesService.INVALID_INVITATION);
		if (token != null) {
			assertThat(response.getContentAsString()).doesNotContain(token);
		}
	}

	private Cookie login(String username) throws Exception {
		var csrfResponse = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
		var token = json.readTree(csrfResponse.getContentAsString());
		return mvc.perform(post("/api/v1/auth/login").cookie(csrfResponse.getCookie("SESSION"))
				.header(token.get("headerName").asString(), token.get("token").asString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("username", username, "password", "password"))))
				.andExpect(status().isNoContent()).andReturn().getResponse().getCookie("SESSION");
	}

}
