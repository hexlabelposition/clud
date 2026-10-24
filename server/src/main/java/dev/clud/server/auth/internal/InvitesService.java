package dev.clud.server.auth.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import dev.clud.server.users.AccountLookup;
import dev.clud.server.users.Role;

@Service
class InvitesService {

	static final String INVALID_INVITATION = "urn:clud:problem:invalid-invitation";

	static final Duration DEFAULT_LIFETIME = Duration.ofDays(7);

	static final Duration MAX_LIFETIME = Duration.ofDays(90);

	private static final Pattern TOKEN_FORMAT = Pattern.compile("clud_inv_[A-Za-z0-9_-]{43}");

	private final SecureRandom random = new SecureRandom();

	private final InviteRepository invites;

	private final AccountLookup accounts;

	private final Clock clock;

	InvitesService(InviteRepository invites, AccountLookup accounts, Clock clock) {
		this.invites = invites;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Transactional
	CreatedInvite create(UUID creator, Instant requestedExpiry) {
		// Read the current account: sessions are not revoked on role changes or when
		// an account is disabled (M7-01), so a stale session may still carry ROLE_ADMIN.
		var account = accounts.findById(creator).filter(found -> !found.disabled())
				.orElseThrow(() -> new BadCredentialsException("Account disabled or no longer exists"));
		if (account.role() != Role.ADMIN) {
			throw new AccessDeniedException("Administrator required");
		}
		var now = clock.instant();
		var expiresAt = (requestedExpiry == null ? now.plus(DEFAULT_LIFETIME) : requestedExpiry)
				.truncatedTo(ChronoUnit.MICROS);
		if (!expiresAt.isAfter(now)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry must be in the future");
		}
		// A leaked token stays usable until it expires; keep that window bounded.
		if (expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry must be at most 90 days ahead");
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = "clud_inv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		var invite = invites.saveAndFlush(new Invite(hash(token), creator, expiresAt));
		return new CreatedInvite(invite.getId(), token, invite.getExpiresAt());
	}

	/** Rejects tokens that cannot exist, before the caller spends time on bcrypt. */
	static void requireWellFormed(String token) {
		if (!TOKEN_FORMAT.matcher(token).matches()) {
			throw invalidInvitation();
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	Invite lockValid(String token) {
		requireWellFormed(token);
		return invites.findByTokenHash(hash(token)).filter(invite -> invite.isUsable(clock.instant()))
				.orElseThrow(InvitesService::invalidInvitation);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void use(Invite invite, UUID userId) {
		var now = clock.instant();
		// Waiting for the users lock can cross the expiry boundary.
		// Throwing rolls back account creation as well as invite consumption.
		if (!invite.isUsable(now)) {
			throw invalidInvitation();
		}
		invite.use(userId, now);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static ErrorResponseException invalidInvitation() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Invalid or unavailable invitation");
		problem.setType(URI.create(INVALID_INVITATION));
		return new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
	}

	record CreatedInvite(UUID id, String token, Instant expiresAt) {
	}

}
