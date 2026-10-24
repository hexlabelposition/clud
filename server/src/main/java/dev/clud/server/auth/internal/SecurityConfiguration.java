package dev.clud.server.auth.internal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			SecurityContextRepository contexts, CsrfTokenRepository csrfTokens,
			AuthRateLimiter limiter, ClientAddressResolver addresses) {
		return http
				.addFilterBefore(new AuthRateLimitFilter(limiter, addresses, exceptionResolver), CsrfFilter.class)
				.securityContext(context -> context.securityContextRepository(contexts))
				// JSON clients handle navigation themselves; do not create a session
				// just to remember an unauthorized request or expose form logout.
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.logout(logout -> logout.disable())
				// Setup does not authenticate via cookies or establish a session and
				// only accepts JSON. Keep CSRF protection for all other mutations.
				.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).ignoringRequestMatchers("/api/v1/setup"))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/v1/health", "/api/v1/openapi", "/api/v1/openapi.yaml", "/api/v1/setup").permitAll()
						.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
						.requestMatchers("/api/v1/auth/registration", "/api/v1/auth/register").permitAll()
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
