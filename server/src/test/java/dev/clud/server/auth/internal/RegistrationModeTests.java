package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import dev.clud.server.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * Registration across the three modes: discovery, first-run setup,
 * registration without, with a valid and with an unusable invitation, and
 * invitation creation. The outer class runs in the default invite mode.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationModeTests {

	private static final String VALID_REQUEST = """
			{"username":"Alice","password":"password"}
			""";

	private static final String UNKNOWN_TOKEN = "clud_inv_" + "A".repeat(43);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper json;

	UUID adminId;

	@BeforeEach
	void createAdmin() {
		clearData();
		adminId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, created_at)
				VALUES (?, 'admin', 'encoded-password', 'ADMIN', CURRENT_TIMESTAMP)
				""", adminId);
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM invites");
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void reportsInviteModeByDefault() throws Exception {
		assertThat(mode()).isEqualTo("INVITE");
	}

	@Test
	void requiresInvitationAfterValidatingRequest() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		assertProblemType(register(null), RegistrationController.INVITATION_REQUIRED);
		assertUsers(1);
	}

	@Test
	void registersWithValidInvitationAndConsumesIt() throws Exception {
		String token = createInvite();
		assertThat(register(token).getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNotNull();
		assertUsers(2);
	}

	@Test
	void rejectsUnknownInvitation() throws Exception {
		assertProblemType(register(UNKNOWN_TOKEN), InvitesService.INVALID_INVITATION);
		assertUsers(1);
	}

	@Test
	void requiresInvitationEvenBeforeSetup() throws Exception {
		jdbc.update("DELETE FROM users");
		assertProblemType(register(null), RegistrationController.INVITATION_REQUIRED);
		assertUsers(0);
	}

	@Test
	void setupRemainsAvailable() throws Exception {
		assertSetupRemainsAvailable();
	}

	@Test
	void csrfFailureHasNoRegistrationProblemType() throws Exception {
		var response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().isForbidden()).andReturn().getResponse();
		var type = json.readTree(response.getContentAsString()).get("type");
		assertThat(type == null || type.asString().equals("about:blank")).isTrue();
	}

	@Nested
	@TestPropertySource(properties = "clud.registration-mode=open")
	class Open {

		@Test
		void reportsOpenMode() throws Exception {
			assertThat(mode()).isEqualTo("OPEN");
		}

		@Test
		void registersWithoutInvitation() throws Exception {
			assertThat(register(null).getStatus()).isEqualTo(201);
			assertUsers(2);
		}

		@Test
		void consumesSuppliedInvitationAndRefusesItsReuse() throws Exception {
			String token = createInvite();
			assertThat(register(token).getStatus()).isEqualTo(201);
			var reuse = mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("username", "Bob", "password", "password", "inviteToken", token))))
					.andReturn().getResponse();
			assertProblemType(reuse, InvitesService.INVALID_INVITATION);
			assertThat(reuse.getContentAsString()).doesNotContain(token);
			assertUsers(2);
		}

		@Test
		void validatesSuppliedInvitationInsteadOfIgnoringIt() throws Exception {
			assertProblemType(register(UNKNOWN_TOKEN), InvitesService.INVALID_INVITATION);
			assertUsers(1);
		}

		@Test
		void rejectsRegistrationBeforeSetup() throws Exception {
			jdbc.update("DELETE FROM users");
			assertThat(register(null).getStatus()).isEqualTo(409);
			assertUsers(0);
		}

		@Test
		void setupRemainsAvailable() throws Exception {
			assertSetupRemainsAvailable();
		}

	}

	@Nested
	@TestPropertySource(properties = "clud.registration-mode=closed")
	class Closed {

		@Test
		void reportsClosedMode() throws Exception {
			assertThat(mode()).isEqualTo("CLOSED");
		}

		@ParameterizedTest
		@ValueSource(strings = { VALID_REQUEST, "{}", "{" })
		void rejectsBeforeReadingRequest(String body) throws Exception {
			assertProblemType(mvc.perform(post("/api/v1/auth/register").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse(),
					RegistrationController.REGISTRATION_CLOSED);
			assertUsers(1);
		}

		@Test
		void refusesValidInvitationWithoutConsumingIt() throws Exception {
			String token = createInvite();
			assertProblemType(register(token), RegistrationController.REGISTRATION_CLOSED);
			assertThat(jdbc.queryForObject("SELECT used_at FROM invites", Instant.class)).isNull();
			assertUsers(1);
		}

		@Test
		void administratorCanStillCreateInvitations() throws Exception {
			assertThat(createInvite()).startsWith("clud_inv_");
		}

		@Test
		void setupRemainsAvailable() throws Exception {
			assertSetupRemainsAvailable();
		}

	}

	private String mode() throws Exception {
		var response = mvc.perform(get("/api/v1/auth/registration")).andExpect(status().isOk()).andReturn().getResponse();
		return json.readTree(response.getContentAsString()).get("mode").asString();
	}

	private MockHttpServletResponse register(String inviteToken) throws Exception {
		String body = inviteToken == null ? VALID_REQUEST
				: json.writeValueAsString(Map.of("username", "Alice", "password", "password", "inviteToken", inviteToken));
		return mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn().getResponse();
	}

	private String createInvite() throws Exception {
		var response = mvc.perform(post("/api/v1/admin/invites").with(user(adminId.toString()).roles("ADMIN"))
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isCreated()).andReturn().getResponse();
		return json.readTree(response.getContentAsString()).get("token").asString();
	}

	/** The first-run administrator setup works in every mode on an empty instance. */
	private void assertSetupRemainsAvailable() throws Exception {
		jdbc.update("DELETE FROM users");
		mvc.perform(post("/api/v1/setup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"root\",\"password\":\"password\"}")).andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("ADMIN");
	}

	private void assertUsers(int count) {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(count);
	}

	private void assertProblemType(MockHttpServletResponse response, String type) throws Exception {
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(json.readTree(response.getContentAsString()).get("type").asString()).isEqualTo(type);
	}

}
