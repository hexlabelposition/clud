package dev.clud.server.auth.internal;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;

@Tag(name = "Invites")
@RestController
class InviteController {

	private final InvitesService invites;

	InviteController(InvitesService invites) {
		this.invites = invites;
	}

	@PostMapping(value = "/api/v1/admin/invites", consumes = "application/json")
	@Operation(description = "Administrator creates a single-use invitation. The token is returned only here; "
			+ "only its SHA-256 hash is stored. Default expiry is seven days, at most 90. Closed registration refuses redemption.")
	@Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
			description = "Token from GET /api/v1/auth/csrf", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "201", description = "Invitation created; keep the token",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = InviteResponse.class)))
	@ApiResponse(responseCode = "400", description = "Invalid expiry or request",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Authentication required or account unavailable",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Administrator required or missing or invalid CSRF token",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<InviteResponse> createInvite(@Valid @RequestBody CreateInviteRequest request, Principal principal) {
		var created = invites.create(UUID.fromString(principal.getName()), request.expiresAt());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(new InviteResponse(created.id(), created.token(), created.expiresAt()));
	}

	record CreateInviteRequest(@Future @Schema(description = "Future expiry, at most 90 days ahead; defaults to seven days from creation") Instant expiresAt) {
	}

	record InviteResponse(
			@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
			@Schema(requiredMode = RequiredMode.REQUIRED, description = "Secret token, returned only on creation") String token,
			@Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {
	}

}
