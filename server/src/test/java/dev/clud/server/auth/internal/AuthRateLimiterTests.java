package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.clud.server.auth.internal.AuthRateLimiter.Endpoint;

class AuthRateLimiterTests {

	private final Clock clock = mock(Clock.class);

	private final Instant start = Instant.parse("2026-10-24T12:00:00Z");

	private final AuthRateLimiter limiter = new AuthRateLimiter(
			new AuthRateLimitProperties(2, 1, 3, 60, 4, List.of()), clock);

	@Test
	void keepsSeparateLimitsPerAddressAndEndpoint() {
		when(clock.instant()).thenReturn(start);
		assertThat(limiter.acquire(Endpoint.LOGIN, "192.0.2.1")).isZero();
		assertThat(limiter.acquire(Endpoint.LOGIN, "192.0.2.1")).isZero();
		assertThat(limiter.acquire(Endpoint.LOGIN, "192.0.2.1")).isEqualTo(60);
		assertThat(limiter.acquire(Endpoint.LOGIN, "192.0.2.2")).isZero();
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isZero();
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isEqualTo(60);
	}

	@Test
	void roundsRetryDelayUpAndBlockedRequestsDoNotExtendWindow() {
		when(clock.instant()).thenReturn(start);
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isZero();
		when(clock.instant()).thenReturn(start.plusNanos(1));
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isEqualTo(60);
		when(clock.instant()).thenReturn(start.plusMillis(59001));
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isEqualTo(1);
		when(clock.instant()).thenReturn(start.plusSeconds(60));
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isZero();
	}

	@Test
	void boundsMemoryWithoutEvictingAnActiveLimitAndReclaimsExpiredWindows() {
		when(clock.instant()).thenReturn(start);
		for (int i = 1; i <= 4; i++) {
			assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2." + i)).isZero();
		}
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.5")).isPositive();
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.1")).isEqualTo(60);
		when(clock.instant()).thenReturn(start.plusSeconds(60));
		assertThat(limiter.acquire(Endpoint.REGISTER, "192.0.2.5")).isZero();
	}

	@Test
	void fullTableForOneEndpointDoesNotBlockNewClientsOfAnother() {
		when(clock.instant()).thenReturn(start);
		for (int i = 1; i <= 4; i++) {
			assertThat(limiter.acquire(Endpoint.CSRF, "192.0.2." + i)).isZero();
		}
		assertThat(limiter.acquire(Endpoint.CSRF, "192.0.2.5")).isPositive();
		assertThat(limiter.acquire(Endpoint.LOGIN, "192.0.2.5")).isZero();
	}

	@Test
	void concurrentRequestsCannotExceedLimit() throws Exception {
		when(clock.instant()).thenReturn(start);
		try (var executor = Executors.newFixedThreadPool(12)) {
			var ready = new CountDownLatch(12);
			var go = new CountDownLatch(1);
			var requests = IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> {
				ready.countDown();
				assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
				return limiter.acquire(Endpoint.LOGIN, "192.0.2.1");
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			int admitted = 0;
			for (var request : requests) {
				if (request.get(10, TimeUnit.SECONDS) == 0) {
					admitted++;
				}
			}
			assertThat(admitted).isEqualTo(2);
		}
	}

}
