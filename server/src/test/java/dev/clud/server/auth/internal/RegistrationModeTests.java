package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationModeTests {

	private static final String VALID_REQUEST = """
			{"username":"Alice","password":"password"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

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
	void reportsInviteModeByDefault() throws Exception {
		assertThat(mode()).isEqualTo("INVITE");
	}

	@Test
	void requiresInvitationAfterValidatingRequest() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		assertProblemType(mvc.perform(post("/api/v1/auth/register").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().isForbidden()).andReturn().getResponse(), RegistrationController.INVITATION_REQUIRED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@Test
	void csrfFailureHasNoRegistrationProblemType() throws Exception {
		var response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().isForbidden()).andReturn().getResponse();
		var type = json.readTree(response.getContentAsString()).get("type");
		assertThat(type == null || type.asString().equals("about:blank")).isTrue();
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
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isForbidden()).andReturn().getResponse(), RegistrationController.REGISTRATION_CLOSED);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		}

	}

	private String mode() throws Exception {
		var response = mvc.perform(get("/api/v1/auth/registration")).andExpect(status().isOk()).andReturn().getResponse();
		return json.readTree(response.getContentAsString()).get("mode").asString();
	}

	private void assertProblemType(MockHttpServletResponse response, String type) throws Exception {
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(json.readTree(response.getContentAsString()).get("type").asString()).isEqualTo(type);
	}

}
