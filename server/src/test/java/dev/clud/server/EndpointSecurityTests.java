package dev.clud.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Checks every API endpoint the Server maps, so new endpoints are secure by
 * default: anonymous requests are refused unless the endpoint is listed as
 * public, and state-changing requests are refused before reaching a controller
 * without a valid CSRF token unless the endpoint is listed as exempt. Adding
 * an endpoint to either list is a deliberate security decision.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EndpointSecurityTests {

	private static final Set<String> PUBLIC = Set.of(
			"GET /api/v1/health",
			"GET /api/v1/openapi",
			"GET /api/v1/openapi.yaml",
			"GET /api/v1/setup",
			"POST /api/v1/setup",
			"GET /api/v1/auth/csrf",
			"POST /api/v1/auth/login",
			"POST /api/v1/auth/logout",
			"GET /api/v1/auth/registration",
			"POST /api/v1/auth/register");

	// Setup neither authenticates by cookie nor starts a session (ARCHITECTURE §6.2).
	private static final Set<String> CSRF_EXEMPT = Set.of("POST /api/v1/setup");

	private static final Set<RequestMethod> SAFE_METHODS = Set.of(RequestMethod.GET, RequestMethod.HEAD,
			RequestMethod.OPTIONS, RequestMethod.TRACE);

	@Autowired
	MockMvc mvc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void clearData() {
		jdbc.update("DELETE FROM invites");
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void listsOnlyEndpointsThatExist() {
		var mapped = endpoints().stream().map(Endpoint::toString).toList();
		assertThat(mapped).containsAll(PUBLIC).containsAll(CSRF_EXEMPT);
	}

	@Test
	void refusesAnonymousRequestsToNonPublicEndpoints() throws Exception {
		List<String> failures = new ArrayList<>();
		for (Endpoint endpoint : endpoints()) {
			if (PUBLIC.contains(endpoint.toString())) {
				continue;
			}
			// A valid token, so that authentication rather than CSRF refuses the request.
			int status = mvc.perform(endpoint.toRequest().with(csrf())).andReturn().getResponse().getStatus();
			if (status != 401) {
				failures.add(endpoint + " answered " + status);
			}
		}
		assertThat(failures).as("anonymous requests").isEmpty();
	}

	@Test
	void refusesStateChangesWithoutValidCsrfTokenBeforeTheController() throws Exception {
		String admin = UUID.randomUUID().toString();
		List<String> failures = new ArrayList<>();
		for (Endpoint endpoint : endpoints()) {
			if (endpoint.safe() || CSRF_EXEMPT.contains(endpoint.toString())) {
				continue;
			}
			MvcResult missing = mvc.perform(endpoint.toRequest().with(user(admin).roles("ADMIN"))).andReturn();
			MvcResult invalid = mvc.perform(endpoint.toRequest().with(user(admin).roles("ADMIN"))
					.with(csrf().useInvalidToken())).andReturn();
			MvcResult valid = mvc.perform(endpoint.toRequest().with(user(admin).roles("ADMIN")).with(csrf())).andReturn();
			if (!refusedByCsrf(missing) || !refusedByCsrf(invalid)) {
				failures.add(endpoint + " accepted a request without a valid token");
			}
			// Proves the refusals above came from CSRF, not from the endpoint itself.
			if (valid.getHandler() == null) {
				failures.add(endpoint + " was not reached with a valid token");
			}
		}
		assertThat(failures).as("state-changing requests").isEmpty();
	}

	@Test
	void exemptEndpointsAreReachedWithoutToken() throws Exception {
		for (String exempt : CSRF_EXEMPT) {
			var endpoint = endpoints().stream().filter(candidate -> candidate.toString().equals(exempt)).findFirst()
					.orElseThrow();
			assertThat(mvc.perform(endpoint.toRequest()).andReturn().getHandler()).as(exempt).isNotNull();
		}
	}

	private static boolean refusedByCsrf(MvcResult result) {
		// The security filter chain refuses the request before the DispatcherServlet
		// selects a handler.
		return result.getResponse().getStatus() == 403 && result.getHandler() == null;
	}

	private Set<Endpoint> endpoints() {
		Set<Endpoint> endpoints = new TreeSet<>();
		mappings.getHandlerMethods().keySet().forEach(mapping -> {
			Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
			if (methods.isEmpty()) {
				methods = Set.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
						RequestMethod.DELETE);
			}
			for (String path : mapping.getPatternValues()) {
				if (path.startsWith("/api/")) {
					methods.forEach(method -> endpoints.add(new Endpoint(method, path)));
				}
			}
		});
		return endpoints;
	}

	record Endpoint(RequestMethod method, String path) implements Comparable<Endpoint> {

		boolean safe() {
			return SAFE_METHODS.contains(method);
		}

		MockHttpServletRequestBuilder toRequest() {
			// Any identifier will do: requests must be refused before it is looked up.
			String uri = path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
			return request(HttpMethod.valueOf(method.name()), uri).contentType(MediaType.APPLICATION_JSON).content("{}");
		}

		@Override
		public int compareTo(Endpoint other) {
			return toString().compareTo(other.toString());
		}

		@Override
		public String toString() {
			return method + " " + path;
		}

	}

}
