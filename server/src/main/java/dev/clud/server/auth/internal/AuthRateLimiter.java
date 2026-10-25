package dev.clud.server.auth.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
class AuthRateLimiter {

	private final AuthRateLimitProperties properties;

	private final Clock clock;

	// Each endpoint has its own capacity, so flooding the cheap CSRF endpoint with
	// new addresses cannot stop new clients from logging in.
	private final Map<Endpoint, Map<String, Window>> windows = new EnumMap<>(Endpoint.class);

	private Instant nextCleanup = Instant.MIN;

	AuthRateLimiter(AuthRateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		for (var endpoint : Endpoint.values()) {
			windows.put(endpoint, new HashMap<>());
		}
	}

	/** Returns zero when admitted, otherwise the minimum retry delay in seconds. */
	synchronized long acquire(Endpoint endpoint, String client) {
		var now = clock.instant();
		// Bound both memory and cleanup work under a stream of new addresses. Never
		// evict a live window: rotating addresses must not reset another IP's limit.
		if (!now.isBefore(nextCleanup)) {
			windows.values().forEach(byClient -> byClient.values().removeIf(window -> !now.isBefore(window.resetsAt)));
			nextCleanup = now.plusSeconds(1);
		}
		var byClient = windows.get(endpoint);
		var window = byClient.get(client);
		if (window == null || !now.isBefore(window.resetsAt)) {
			if (window == null && byClient.size() >= properties.maxBuckets()) {
				return 1;
			}
			window = new Window(now.plusSeconds(properties.windowSeconds()));
			byClient.put(client, window);
		}
		if (window.requests >= endpoint.limit(properties)) {
			// The window is still open, so the remainder is positive; round it up.
			long nanos = Duration.between(now, window.resetsAt).toNanos();
			return (nanos + 999_999_999) / 1_000_000_000;
		}
		window.requests++;
		return 0;
	}

	enum Endpoint {
		LOGIN, REGISTER, PASSWORD, CSRF;

		int limit(AuthRateLimitProperties properties) {
			return switch (this) {
				case LOGIN -> properties.loginRequests();
				case REGISTER -> properties.registerRequests();
				case PASSWORD -> properties.passwordRequests();
				case CSRF -> properties.csrfRequests();
			};
		}
	}

	private static class Window {

		final Instant resetsAt;

		int requests;

		Window(Instant resetsAt) {
			this.resetsAt = resetsAt;
		}

	}

}
