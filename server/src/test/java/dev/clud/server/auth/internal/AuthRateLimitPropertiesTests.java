package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class AuthRateLimitPropertiesTests {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void usesConservativeDefaultsAndTrustsNoProxy() {
		context.run(application -> {
			var properties = application.getBean(AuthRateLimitProperties.class);
			assertThat(properties.loginRequests()).isEqualTo(10);
			assertThat(properties.registerRequests()).isEqualTo(5);
			assertThat(properties.passwordRequests()).isEqualTo(5);
			assertThat(properties.csrfRequests()).isEqualTo(60);
			assertThat(properties.windowSeconds()).isEqualTo(60);
			assertThat(properties.maxBuckets()).isEqualTo(10000);
			assertThat(properties.trustedProxies()).isEmpty();
		});
	}

	@Test
	void bindsDocumentedEnvironmentVariables() {
		context.withInitializer(application -> application.getEnvironment().getPropertySources().addFirst(
				new SystemEnvironmentPropertySource("rate-limit-test", Map.of(
						"CLUD_AUTH_RATE_LIMIT_LOGIN_REQUESTS", "12",
						"CLUD_AUTH_RATE_LIMIT_REGISTER_REQUESTS", "6",
						"CLUD_AUTH_RATE_LIMIT_PASSWORD_REQUESTS", "7",
						"CLUD_AUTH_RATE_LIMIT_CSRF_REQUESTS", "80",
						"CLUD_AUTH_RATE_LIMIT_WINDOW_SECONDS", "120",
						"CLUD_AUTH_RATE_LIMIT_MAX_BUCKETS", "100",
						"CLUD_AUTH_RATE_LIMIT_TRUSTED_PROXIES", "127.0.0.1/32,::1/128"))))
				.run(application -> {
					var properties = application.getBean(AuthRateLimitProperties.class);
					assertThat(properties.loginRequests()).isEqualTo(12);
					assertThat(properties.registerRequests()).isEqualTo(6);
					assertThat(properties.passwordRequests()).isEqualTo(7);
					assertThat(properties.csrfRequests()).isEqualTo(80);
					assertThat(properties.windowSeconds()).isEqualTo(120);
					assertThat(properties.maxBuckets()).isEqualTo(100);
					assertThat(properties.trustedProxies()).containsExactly("127.0.0.1/32", "::1/128");
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "login-requests=0", "register-requests=-1", "password-requests=0", "csrf-requests=0", "window-seconds=0",
			"window-seconds=86401", "max-buckets=0", "max-buckets=1000001", "trusted-proxies=example.com",
			"trusted-proxies=192.0.2.1/33" })
	void rejectsInvalidConfiguration(String property) {
		context.withPropertyValues("clud.auth.rate-limit." + property).run(application -> assertThat(application).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AuthRateLimitProperties.class)
	@Import(ClientAddressResolver.class)
	static class PropertiesConfiguration {
	}

}
