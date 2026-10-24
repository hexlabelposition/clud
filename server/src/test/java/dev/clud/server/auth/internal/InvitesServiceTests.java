package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import dev.clud.server.users.AccountCredentials;
import dev.clud.server.users.AccountLookup;
import dev.clud.server.users.Role;

class InvitesServiceTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final UUID adminId = UUID.randomUUID();

	private final InviteRepository repository = mock(InviteRepository.class);

	private final Clock clock = mock(Clock.class);

	private final InvitesService invites;

	InvitesServiceTests() {
		var accounts = mock(AccountLookup.class);
		when(accounts.findById(adminId)).thenReturn(Optional.of(new AccountCredentials(adminId, "hash", Role.ADMIN, false)));
		when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
		when(clock.instant()).thenReturn(NOW);
		invites = new InvitesService(repository, accounts, clock);
	}

	@Test
	void defaultsToSevenDays() {
		assertThat(invites.create(adminId, null).expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
	}

	@Test
	void acceptsExpiryUpToNinetyDays() {
		var limit = NOW.plus(Duration.ofDays(90));
		assertThat(invites.create(adminId, limit).expiresAt()).isEqualTo(limit);
		assertThatThrownBy(() -> invites.create(adminId, limit.plusNanos(1000)))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void refusesInviteThatExpiresBetweenLockAndUse() {
		var invite = new Invite("0".repeat(64), adminId, NOW.plusSeconds(1));
		when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(invite));
		when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1));

		var locked = invites.lockValid("clud_inv_" + "A".repeat(43));
		assertThatThrownBy(() -> invites.use(locked, UUID.randomUUID()))
				.isInstanceOfSatisfying(ErrorResponseException.class,
						error -> assertThat(error.getBody().getType()).hasToString(InvitesService.INVALID_INVITATION));
		assertThat(invite.isUsable(NOW)).isTrue();
	}

}
