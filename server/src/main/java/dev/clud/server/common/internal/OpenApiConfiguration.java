package dev.clud.server.common.internal;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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

}
