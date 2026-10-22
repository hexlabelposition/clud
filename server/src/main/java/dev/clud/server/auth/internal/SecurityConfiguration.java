package dev.clud.server.auth.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	// Authentication (sessions, personal access tokens, CSRF) arrives in M1; until
	// then only health is reachable.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {
		return http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/v1/health").permitAll()
						.anyRequest().authenticated())
				.build();
	}

}
