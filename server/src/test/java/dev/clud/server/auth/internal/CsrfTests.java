package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.clud.server.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

/**
 * Properties of the CSRF token itself; EndpointSecurityTests checks that every
 * state-changing endpoint requires one.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CsrfTests {

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
				VALUES (?, 'admin', ?, 'ADMIN', CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), passwords.encode("password"));
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM invites");
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void tokenIsBoundToTheSessionThatIssuedIt() throws Exception {
		var mine = csrf(null);
		var theirs = csrf(null);
		login(mine.cookie(), theirs).andExpect(status().isForbidden());
		login(mine.cookie(), mine).andExpect(status().isNoContent());
	}

	@Test
	void rejectsForgedTokenAndTokenWithoutSession() throws Exception {
		var csrf = csrf(null);
		login(csrf.cookie(), new CsrfExchange(csrf.cookie(), csrf.header(), "forged-token"))
				.andExpect(status().isForbidden());
		// Without the session cookie the Server has no token to compare with.
		mvc.perform(post("/api/v1/auth/login").header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(loginBody())).andExpect(status().isForbidden());
	}

	@Test
	void everyIssuedTokenOfASessionIsAccepted() throws Exception {
		// Responses mask the token differently each time (BREACH protection), but
		// all of them stay valid, so a client may keep an older one.
		var first = csrf(null);
		var second = csrf(first.cookie());
		assertThat(second.token()).isNotEqualTo(first.token());
		Cookie session = login(first.cookie(), first).andExpect(status().isNoContent())
				.andReturn().getResponse().getCookie("SESSION");
		var token = csrf(session);
		createInvite(session, token).andExpect(status().isCreated());
		createInvite(session, csrf(session)).andExpect(status().isCreated());
		createInvite(session, token).andExpect(status().isCreated());
	}

	@Test
	void oneTokenServesManyMutationsUntilLogout() throws Exception {
		// The Web app may reuse the token for a batch of mutations, e.g. uploads.
		var anonymous = csrf(null);
		Cookie session = login(anonymous.cookie(), anonymous).andExpect(status().isNoContent())
				.andReturn().getResponse().getCookie("SESSION");
		var token = csrf(session);
		for (int i = 0; i < 3; i++) {
			createInvite(session, token).andExpect(status().isCreated());
		}
		mvc.perform(post("/api/v1/auth/logout").cookie(session).header(token.header(), token.token()))
				.andExpect(status().isNoContent());
		createInvite(session, token).andExpect(status().isForbidden());
	}

	@Test
	void safeRequestsNeedNoToken() throws Exception {
		var anonymous = csrf(null);
		Cookie session = login(anonymous.cookie(), anonymous).andExpect(status().isNoContent())
				.andReturn().getResponse().getCookie("SESSION");
		mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());
	}

	private ResultActions login(Cookie cookie, CsrfExchange csrf) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").cookie(cookie).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content(loginBody()));
	}

	private ResultActions createInvite(Cookie session, CsrfExchange csrf) throws Exception {
		return mvc.perform(post("/api/v1/admin/invites").cookie(session).header(csrf.header(), csrf.token())
				.contentType(MediaType.APPLICATION_JSON).content("{}"));
	}

	private String loginBody() throws Exception {
		return json.writeValueAsString(Map.of("username", "admin", "password", "password"));
	}

	private CsrfExchange csrf(Cookie cookie) throws Exception {
		var request = get("/api/v1/auth/csrf");
		if (cookie != null) {
			request.cookie(cookie);
		}
		var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
		var body = json.readTree(response.getContentAsString());
		return new CsrfExchange(cookie == null ? response.getCookie("SESSION") : cookie,
				body.get("headerName").asString(), body.get("token").asString());
	}

	record CsrfExchange(Cookie cookie, String header, String token) {
	}

}
