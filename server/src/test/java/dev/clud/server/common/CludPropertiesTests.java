package dev.clud.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class CludPropertiesTests {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void defaultsToInvite() {
		context.run(application -> assertThat(application.getBean(CludProperties.class).registrationMode())
				.isEqualTo(RegistrationMode.INVITE));
	}

	@ParameterizedTest
	@CsvSource({ "invite,INVITE", "open,OPEN", "closed,CLOSED" })
	void bindsEnvironmentVariable(String value, RegistrationMode expected) {
		context.withInitializer(application -> application.getEnvironment().getPropertySources().addFirst(
				new SystemEnvironmentPropertySource("registration-test", Map.of("CLUD_REGISTRATION_MODE", value))))
				.run(application -> assertThat(application.getBean(CludProperties.class).registrationMode()).isEqualTo(expected));
	}

	@Test
	void rejectsUnknownModeAtStartup() {
		context.withPropertyValues("clud.registration-mode=public").run(application -> assertThat(application).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CludProperties.class)
	static class PropertiesConfiguration {
	}

}
