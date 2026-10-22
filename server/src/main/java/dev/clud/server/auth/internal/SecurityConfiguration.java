package dev.clud.server.auth.internal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	// Authentication (sessions, personal access tokens, CSRF) arrives in M1; until
	// then only health and the API specification are reachable.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		return http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/v1/health", "/api/v1/openapi", "/api/v1/openapi.yaml").permitAll()
						.anyRequest().authenticated())
				// Security errors happen outside Spring MVC; hand them over so they are
				// rendered as Problem Details like any other error.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, ex) -> exceptionResolver
								.resolveException(request, response, null, ex))
						.accessDeniedHandler((request, response, ex) -> exceptionResolver
								.resolveException(request, response, null, ex)))
				.build();
	}

}
