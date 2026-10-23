package dev.clud.server.common.internal;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.info(new Info().title("Clud API").version("v1"))
				// Relative to the instance URL. A fixed value also keeps the generated
				// specification free of the host and port it was generated on.
				.servers(List.of(new Server().url("/")));
	}

	@Bean
	OpenApiCustomizer schemaCleanup() {
		return openApi -> {
			if (openApi.getComponents().getSchemas() == null) {
				return;
			}
			// Spring writes ProblemDetail properties as top-level members, not as a
			// nested "properties" object as springdoc infers from the getter.
			Schema<?> problem = openApi.getComponents().getSchemas().get("ProblemDetail");
			if (problem != null) {
				problem.getProperties().remove("properties");
				problem.setAdditionalProperties(true);
			}
			// @Size(max = ...) also reports its default min = 0, which constrains nothing.
			for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
				if (schema.getProperties() == null) {
					continue;
				}
				for (Schema<?> property : schema.getProperties().values()) {
					if (Integer.valueOf(0).equals(property.getMinLength())) {
						property.setMinLength(null);
					}
				}
			}
		};
	}

}
