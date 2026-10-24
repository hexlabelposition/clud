package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.clud.server.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"clud.registration-mode=open",
		"clud.auth.rate-limit.login-requests=2",
		"clud.auth.rate-limit.register-requests=2",
		"clud.auth.rate-limit.csrf-requests=2",
		"clud.auth.rate-limit.window-seconds=60",
		"clud.auth.rate-limit.trusted-proxies=10.0.0.0/8" })
@AutoConfigureMockMvc
class AuthRateLimitTests {

	private static final AtomicInteger ADDRESSES = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper json;

	@MockitoSpyBean
	PasswordEncoder passwords;

	String address;

	@BeforeEach
	void createAccount() {
		clearData();
		address = "198.51.100." + ADDRESSES.incrementAndGet();
		jdbc.update("""
				INSERT INTO users (id, username, password_hash, role, created_at)
				VALUES (?, 'Alice', ?, 'ADMIN', CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), passwords.encode("password"));
		clearInvocations(passwords);
	}

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM invites");
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void countsFailedAndSuccessfulLoginsAndBlocksBeforeCsrfOrBcrypt() throws Exception {
		var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").with(request -> {
			request.setRemoteAddr(address);
			return request;
		})).andExpect(status().isOk()).andReturn().getResponse();
		var token = json.readTree(csrfResponse.getContentAsString());
		var cookie = csrfResponse.getCookie("SESSION");
		for (String password : new String[] { "wrong", "password" }) {
			var response = mvc.perform(post("/api/v1/auth/login").with(request -> {
				request.setRemoteAddr(address);
				return request;
			}).cookie(cookie).header(token.get("headerName").asString(), token.get("token").asString())
					.contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("username", "aLiCe", "password", password))))
					.andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(password.equals("wrong") ? 401 : 204);
		}
		// No token and malformed JSON: a limited request is refused before either
		// CSRF validation or reading the body, with no password work or session.
		var denied = mvc.perform(post("/api/v1/auth/login").with(request -> {
			request.setRemoteAddr(address);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isTooManyRequests()).andReturn().getResponse();
		assertLimited(denied);
		verify(passwords, times(2)).matches(any(CharSequence.class), anyString());
	}

	@Test
	void limitsSuccessfulRegistrationsBeforeHashingOrAccountCreation() throws Exception {
		for (String name : new String[] { "Bob", "Carol", "Dave" }) {
			var response = mvc.perform(post("/api/v1/auth/register").with(csrf()).with(request -> {
				request.setRemoteAddr(address);
				return request;
			}).contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("username", name, "password", "password"))))
					.andReturn().getResponse();
			if (name.equals("Dave")) {
				assertLimited(response);
			}
			else {
				assertThat(response.getStatus()).isEqualTo(201);
			}
		}
		verify(passwords, times(2)).encode(any(CharSequence.class));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
	}

	@Test
	void limitsUnknownInviteAttemptsBeforeTheirBcryptWork() throws Exception {
		String unknownToken = "clud_inv_" + "A".repeat(43);
		for (int i = 0; i < 3; i++) {
			var response = mvc.perform(post("/api/v1/auth/register").with(csrf()).with(request -> {
				request.setRemoteAddr(address);
				return request;
			}).contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("username", "Bob", "password", "password", "inviteToken", unknownToken))))
					.andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(i < 2 ? 403 : 429);
		}
		verify(passwords, times(2)).encode(any(CharSequence.class));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
	}

	@Test
	void limitsCsrfSessionsIncludingHeadRequests() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/api/v1/auth/csrf").with(request -> {
				request.setRemoteAddr(address);
				return request;
			})).andExpect(status().isOk());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isEqualTo(2);
		var response = mvc.perform(get("/api/v1/auth/csrf").with(request -> {
			request.setRemoteAddr(address);
			return request;
		})).andExpect(status().isTooManyRequests()).andReturn().getResponse();
		assertLimited(response);
		mvc.perform(head("/api/v1/auth/csrf").with(request -> {
			request.setRemoteAddr(address);
			return request;
		})).andExpect(status().isTooManyRequests());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isEqualTo(2);
	}

	@Test
	void badCsrfConsumesBudgetAndForwardedHeadersCannotResetIt() throws Exception {
		for (int i = 0; i < 3; i++) {
			var response = mvc.perform(post("/api/v1/auth/login").with(request -> {
				request.setRemoteAddr(address);
				return request;
			}).header("X-Forwarded-For", "192.0.2." + i).header("Forwarded", "for=192.0.2." + i)
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(i < 2 ? 403 : 429);
		}
		verifyNoInteractions(passwords);
	}

	@Test
	void trustedProxyKeepsSeparateClientBudgetsAndEndpointBudgets() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/v1/auth/login").with(csrf()).with(request -> {
				request.setRemoteAddr("10.1.2.3");
				return request;
			}).header("X-Forwarded-For", address).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(i < 2 ? status().isBadRequest() : status().isTooManyRequests());
		}
		mvc.perform(post("/api/v1/auth/login").with(csrf()).with(request -> {
			request.setRemoteAddr("10.1.2.3");
			return request;
		}).header("X-Forwarded-For", "192.0.2.1").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/register").with(csrf()).with(request -> {
			request.setRemoteAddr("10.1.2.3");
			return request;
		}).header("X-Forwarded-For", address).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/auth/csrf").with(request -> {
			request.setRemoteAddr("10.1.2.3");
			return request;
		}).header("X-Forwarded-For", address)).andExpect(status().isOk());
	}

	@Test
	void encodedPathsAndContextPathsUseSameBudgetAndOtherEndpointsKeepWorking() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/clud/api/v1/auth/login").contextPath("/clud").with(csrf()).with(request -> {
				request.setRemoteAddr(address);
				return request;
			}).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(post(URI.create("/api/v1/auth/%6cogin")).with(csrf()).with(request -> {
			request.setRemoteAddr(address);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isTooManyRequests());
		mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/auth/registration")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/auth/logout").with(csrf()).with(request -> {
			request.setRemoteAddr(address);
			return request;
		})).andExpect(status().isNoContent());
	}

	private void assertLimited(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(response.getCookie("SESSION")).isNull();
		var body = json.readTree(response.getContentAsString());
		assertThat(body.get("type").asString()).isEqualTo(AuthRateLimitFilter.RATE_LIMIT_EXCEEDED);
		assertThat(body.get("status").asInt()).isEqualTo(429);
		assertThat(response.getContentAsString()).doesNotContain("password", address);
	}

}
