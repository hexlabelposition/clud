package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import dev.clud.server.ServerApplication;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class SessionRestartTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	@Test
	void sessionSurvivesCompleteServerRestart() throws Exception {
		UUID userId = UUID.randomUUID();
		String cookie;
		try (HttpClient client = HttpClient.newHttpClient()) {
			try (var server = startServer("https://clud.example")) {
				server.getBean(JdbcTemplate.class).update("""
						INSERT INTO users (id, username, password_hash, role, created_at)
						VALUES (?, 'alice', ?, 'USER', CURRENT_TIMESTAMP)
						""", userId, server.getBean(PasswordEncoder.class).encode("password"));
				String baseUrl = "http://localhost:" + server.getWebServer().getPort();
				var csrf = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/csrf")).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				assertThat(csrf.statusCode()).isEqualTo(200);
				var token = server.getBean(ObjectMapper.class).readTree(csrf.body());
				var login = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
						.header("Cookie", sessionCookie(csrf))
						.header(token.get("headerName").asString(), token.get("token").asString())
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"password\"}"))
						.build(), HttpResponse.BodyHandlers.ofString());
				assertThat(login.statusCode()).isEqualTo(204);
				String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
				assertThat(setCookie).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
				cookie = sessionCookie(login);
			}

			// New web server, pool, session repository and security filter chain;
			// only PostgreSQL and the browser's cookie survive.
			try (var server = startServer("https://clud.example")) {
				String baseUrl = "http://localhost:" + server.getWebServer().getPort();
				var result = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/test/restart-session"))
						.header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
				assertThat(result.statusCode()).isEqualTo(200);
				assertThat(result.body()).isEqualTo(userId.toString());
			}
		}
	}

	// Cookie attributes are applied only with an embedded web server, so they are
	// checked here rather than with MockMvc.
	@Test
	void sessionCookieOmitsSecureForHttpPublicUrl() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient(); var server = startServer("http://nas.local")) {
			var csrf = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getWebServer().getPort()
					+ "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertThat(csrf.headers().firstValue("Set-Cookie").orElseThrow()).startsWith("SESSION=")
					.contains("HttpOnly", "SameSite=Lax").doesNotContain("Secure");
		}
	}

	private ServletWebServerApplicationContext startServer(String publicUrl) {
		return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerApplication.class, ProtectedController.class)
				.run("--server.port=0", "--clud.public-url=" + publicUrl,
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword());
	}

	private String sessionCookie(HttpResponse<?> response) {
		return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
				.findFirst().orElseThrow().split(";", 2)[0];
	}

	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class ProtectedController {

		@GetMapping("/api/v1/test/restart-session")
		String principal(Principal principal) {
			return principal.getName();
		}

	}

}
