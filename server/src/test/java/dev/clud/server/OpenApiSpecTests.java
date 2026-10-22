package dev.clud.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Keeps {@code api/openapi.yaml} in sync with the code. Fails when the committed
 * specification is out of date; {@code ./gradlew openapi} rewrites it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void specificationIsUpToDate() throws IOException {
		Path file = Path.of(System.getProperty("clud.openapi.file"));
		String generated = mvc.get().uri("/api/v1/openapi.yaml").exchange().getResponse().getContentAsString();

		if (Boolean.getBoolean("clud.openapi.update")) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, generated);
			return;
		}

		assertThat(file).as("api/openapi.yaml is missing, run ./gradlew openapi").exists();
		assertThat(Files.readString(file))
				.as("api/openapi.yaml is out of date, run ./gradlew openapi")
				.isEqualTo(generated);
	}

}
