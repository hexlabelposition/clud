package dev.clud.server.users.internal;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.clud.server.users.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@Tag(name = "Users")
@RestController
class MeController {

	private final UserRepository users;

	MeController(UserRepository users) {
		this.users = users;
	}

	@GetMapping("/api/v1/me")
	@Operation(description = "Returns the current user's profile from the database. Requires an authenticated session.")
	@ApiResponse(responseCode = "200", description = "Current user profile",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = MeResponse.class)))
	@ApiResponse(responseCode = "401", description = "Authentication required, account disabled or no longer exists",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	MeResponse currentUser(Principal principal, HttpServletRequest request) {
		var user = users.findById(UUID.fromString(principal.getName()))
				.filter(found -> !found.isDisabled())
				.orElseThrow(() -> {
					// Drop the session so a polling client cannot keep it alive until it
					// expires for an account that may no longer use it.
					var session = request.getSession(false);
					if (session != null) {
						session.invalidate();
					}
					return new BadCredentialsException("Account disabled or no longer exists");
				});
		return new MeResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
				user.getQuotaBytes(), user.getCreatedAt());
	}

	record MeResponse(
			@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
			@Schema(requiredMode = RequiredMode.REQUIRED) String username,
			@Schema(requiredMode = RequiredMode.REQUIRED, types = { "string", "null" }, format = "email") String email,
			@Schema(requiredMode = RequiredMode.REQUIRED) Role role,
			@Schema(requiredMode = RequiredMode.REQUIRED, types = { "integer", "null" }, format = "int64",
					minimum = "0", description = "Storage limit in bytes; null means unlimited, zero allows no storage") Long quotaBytes,
			@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
	}

}
