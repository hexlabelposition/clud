package dev.clud.server.common.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import dev.clud.server.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void reportsUpWithoutAuthentication() {
		assertThat(mvc.get().uri("/api/v1/health"))
				.hasStatus(HttpStatus.OK)
				.bodyJson().extractingPath("$.status").isEqualTo("UP");
	}

}
