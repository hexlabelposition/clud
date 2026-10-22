package dev.clud.server.auth.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		// Store the algorithm prefix with each hash so the encoding can evolve.
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
