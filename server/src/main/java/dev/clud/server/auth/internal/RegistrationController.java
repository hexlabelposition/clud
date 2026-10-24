package dev.clud.server.auth.internal;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.clud.server.common.CludProperties;
import dev.clud.server.common.RegistrationMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
class RegistrationController {

	static final String REGISTRATION_CLOSED = "urn:clud:problem:registration-closed";

	static final String INVITATION_REQUIRED = "urn:clud:problem:invitation-required";

	private final CludProperties properties;

	private final RegistrationService registration;

	RegistrationController(CludProperties properties, RegistrationService registration) {
		this.properties = properties;
		this.registration = registration;
	}

	@GetMapping("/registration")
	@Operation(description = "Reports the registration mode so clients can decide whether to offer registration.")
	RegistrationStatus registrationStatus() {
		return new RegistrationStatus(properties.registrationMode());
	}

	@PostMapping(value = "/register", consumes = "application/json")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(description = "Creates a USER account after first-run setup without starting a session. "
			+ "Invite mode requires inviteToken; open mode allows registration without one. "
			+ "A supplied token is validated and consumed in either mode. Closed mode always refuses registration.")
	@Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
			description = "Token from GET /api/v1/auth/csrf", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "201", description = "Account created; log in to start a session", content = @Content)
	@ApiResponse(responseCode = "400", description = "Invalid account details",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Registration closed (type " + REGISTRATION_CLOSED + "), "
			+ "invitation required (type " + INVITATION_REQUIRED + "), invalid, expired or used invitation (type "
			+ InvitesService.INVALID_INVITATION + "), or missing or invalid CSRF token",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "First-run setup required or username already taken",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	void register(@Valid @RequestBody RegistrationRequest request) {
		// RegistrationConfiguration rejects closed mode before the body is read; check
		// it here too so the endpoint stays safe without the interceptor.
		switch (properties.registrationMode()) {
			case CLOSED -> throw registrationClosed();
			case INVITE -> {
				if (request.inviteToken() == null) {
					throw forbidden(INVITATION_REQUIRED, "Registration requires an invitation");
				}
			}
			case OPEN -> { }
		}
		switch (registration.register(request.username(), request.email(), request.password(), request.inviteToken())) {
			case CREATED -> { }
			case SETUP_REQUIRED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "First-run setup required");
			case USERNAME_TAKEN -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
		}
	}

	static ErrorResponseException registrationClosed() {
		return forbidden(REGISTRATION_CLOSED, "Registration is closed");
	}

	// A problem type lets clients tell a policy refusal from a CSRF failure, which
	// shares the 403 status but is worth retrying with a fresh token.
	private static ErrorResponseException forbidden(String type, String detail) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
		problem.setType(URI.create(type));
		return new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
	}

	record RegistrationStatus(@Schema(requiredMode = RequiredMode.REQUIRED) RegistrationMode mode) {
	}

	record RegistrationRequest(
			@NotNull @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
			@Email @Size(max = 254) String email,
			@NotBlank @Size(min = 1, max = 72) @BcryptPassword @Schema(description = "Non-blank password, at most 72 bytes of UTF-8",
					format = "password") String password,
			@Size(max = 128) @Schema(description = "Single-use invitation token; required in invite mode", format = "password") String inviteToken) {
	}

}
