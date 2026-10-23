package dev.clud.server.auth.internal;

import java.util.List;

import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import dev.clud.server.common.CludProperties;
import dev.clud.server.users.AccountLookup;

@Configuration(proxyBeanMethods = false)
class SessionAuthenticationConfiguration {

	@Bean
	AuthenticationManager authenticationManager(AccountLookup accounts, PasswordEncoder passwords) {
		var provider = new DaoAuthenticationProvider(username -> {
			var account = accounts.findByUsername(username)
					.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
			// The principal is a stable user ID. ProviderManager erases the password
			// before this User is serialized into the JDBC session.
			return User.withUsername(account.id().toString()).password(account.passwordHash())
					.roles(account.role().name()).disabled(account.disabled()).build();
		});
		provider.setPasswordEncoder(passwords);
		// Check account status only after the password matches, so a disabled account
		// costs the same bcrypt work as any other failed login.
		provider.setPreAuthenticationChecks(user -> {
		});
		provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
		return new ProviderManager(provider);
	}

	// Browsers drop Secure cookies received over plain HTTP (except on localhost), so
	// an instance served at an http:// URL, e.g. on a LAN, could never log in.
	@Bean
	DefaultCookieSerializerCustomizer secureSessionCookie(CludProperties clud) {
		boolean https = "https".equalsIgnoreCase(clud.publicUrl().getScheme());
		return serializer -> serializer.setUseSecureCookie(https);
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return new HttpSessionCsrfTokenRepository();
	}

	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
		return new CompositeSessionAuthenticationStrategy(List.of(
				new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
	}

	@Bean
	LogoutHandler sessionLogoutHandler(CsrfTokenRepository csrfTokens, SecurityContextRepository contexts) {
		var logout = new SecurityContextLogoutHandler();
		logout.setSecurityContextRepository(contexts);
		return new CompositeLogoutHandler(new CsrfLogoutHandler(csrfTokens), logout);
	}

}
