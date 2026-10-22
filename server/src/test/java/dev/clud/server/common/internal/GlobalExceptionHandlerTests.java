package dev.clud.server.common.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.clud.server.TestcontainersConfiguration;

@Import({ TestcontainersConfiguration.class, GlobalExceptionHandlerTests.FailingController.class })
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void rejectsAnonymousRequestWithUnauthorizedProblem() {
		assertThat(mvc.get().uri("/api/v1/unknown"))
				.hasStatus(HttpStatus.UNAUTHORIZED)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson().extractingPath("$.status").isEqualTo(401);
	}

	@Test
	@WithMockUser
	void reportsUnknownPathAsNotFoundProblem() {
		assertThat(mvc.get().uri("/api/v1/unknown"))
				.hasStatus(HttpStatus.NOT_FOUND)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson().extractingPath("$.status").isEqualTo(404);
	}

	@Test
	@WithMockUser
	void hidesDetailsOfUnexpectedException() {
		assertThat(mvc.get().uri("/api/v1/test/failure"))
				.hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyText().doesNotContain("secret");
	}

	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class FailingController {

		@GetMapping("/api/v1/test/failure")
		void fail() {
			throw new IllegalStateException("secret");
		}

	}

}
