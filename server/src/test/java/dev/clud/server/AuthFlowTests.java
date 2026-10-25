package dev.clud.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The first-run journey of milestone M1 through real HTTP, as a browser would
 * make it: the administrator completes setup and invites a user, who
 * registers and logs in; both sessions survive a Server restart; a password
 * change signs out the user's other sessions only.
 */
@Testcontainers
class AuthFlowTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	static final ObjectMapper json = new ObjectMapper();

	@Test
	void adminInvitesUserAndSessionsSurviveRestart() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			var admin = new Browser(client);
			var bob = new Browser(client);
			var bobsLaptop = new Browser(client);

			try (var server = startServer()) {
				admin.baseUrl = bob.baseUrl = bobsLaptop.baseUrl = baseUrl(server);

				assertThat(admin.get("/api/v1/setup").body().get("setupNeeded").asBoolean()).isTrue();
				assertThat(admin.post("/api/v1/setup", Map.of("username", "admin", "password", "admin-password"))
						.statusCode()).isEqualTo(201);
				assertThat(admin.get("/api/v1/setup").body().get("setupNeeded").asBoolean()).isFalse();
				assertThat(admin.login("admin", "admin-password")).isEqualTo(204);

				var invite = admin.post("/api/v1/admin/invites", Map.of());
				assertThat(invite.statusCode()).isEqualTo(201);
				String token = invite.body().get("token").asString();

				assertThat(bob.get("/api/v1/auth/registration").body().get("mode").asString()).isEqualTo("INVITE");
				assertThat(bob.post("/api/v1/auth/register",
						Map.of("username", "bob", "password", "bob-password", "inviteToken", token)).statusCode())
						.isEqualTo(201);
				assertThat(bob.login("bob", "bob-password")).isEqualTo(204);
				assertThat(bobsLaptop.login("bob", "bob-password")).isEqualTo(204);
			}

			// Only PostgreSQL and the browsers' cookies survive.
			try (var server = startServer()) {
				admin.baseUrl = bob.baseUrl = bobsLaptop.baseUrl = baseUrl(server);

				assertThat(admin.get("/api/v1/me").body().get("role").asString()).isEqualTo("ADMIN");
				assertThat(bob.get("/api/v1/me").body().get("username").asString()).isEqualTo("bob");
				assertThat(bobsLaptop.get("/api/v1/me").statusCode()).isEqualTo(200);

				assertThat(bob.post("/api/v1/auth/password",
						Map.of("currentPassword", "bob-password", "newPassword", "bob-new-password")).statusCode())
						.isEqualTo(204);
				assertThat(bob.get("/api/v1/me").statusCode()).isEqualTo(200);
				assertThat(bobsLaptop.get("/api/v1/me").statusCode()).isEqualTo(401);
				assertThat(admin.get("/api/v1/me").statusCode()).isEqualTo(200);

				assertThat(bob.post("/api/v1/auth/logout", null).statusCode()).isEqualTo(204);
				assertThat(bob.get("/api/v1/me").statusCode()).isEqualTo(401);
				assertThat(bob.login("bob", "bob-password")).isEqualTo(401);
				assertThat(bob.login("bob", "bob-new-password")).isEqualTo(204);
			}
		}
	}

	private ServletWebServerApplicationContext startServer() {
		return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerApplication.class)
				.run("--server.port=0", "--clud.public-url=http://localhost",
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword());
	}

	private static String baseUrl(ServletWebServerApplicationContext server) {
		return "http://localhost:" + server.getWebServer().getPort();
	}

	record Response(int statusCode, JsonNode body) {
	}

	/**
	 * Keeps the SESSION cookie between requests and sends a fresh CSRF token with
	 * every mutation, like the Web app.
	 */
	static class Browser {

		private final HttpClient client;

		String baseUrl;

		private String cookie;

		Browser(HttpClient client) {
			this.client = client;
		}

		Response get(String path) throws Exception {
			return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
		}

		Response post(String path, Object body) throws Exception {
			var csrf = get("/api/v1/auth/csrf").body();
			var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
					.header(csrf.get("headerName").asString(), csrf.get("token").asString());
			if (body == null) {
				request.POST(HttpRequest.BodyPublishers.noBody());
			}
			else {
				request.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			}
			return send(request);
		}

		int login(String username, String password) throws Exception {
			return post("/api/v1/auth/login", Map.of("username", username, "password", password)).statusCode();
		}

		private Response send(HttpRequest.Builder request) throws Exception {
			if (cookie != null) {
				request.header("Cookie", cookie);
			}
			var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
					.forEach(value -> {
						String pair = value.split(";", 2)[0];
						// An empty value or Max-Age=0 clears the cookie.
						cookie = pair.equals("SESSION=") || value.contains("Max-Age=0") ? null : pair;
					});
			String text = response.body();
			return new Response(response.statusCode(), text.isEmpty() ? null : json.readTree(text));
		}

	}

}
