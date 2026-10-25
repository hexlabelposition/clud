package dev.clud.server.auth.internal;

import java.security.Principal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Tag(name = "Authentication")
@RestController
class PasswordController {

	private final PasswordService passwords;

	PasswordController(PasswordService passwords) {
		this.passwords = passwords;
	}

	@PostMapping(value = "/api/v1/auth/password", consumes = "application/json")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(description = "Changes the authenticated user's password after verifying the current password. "
			+ "Other sessions are revoked. The current session continues under a new ID; keep the new SESSION cookie. "
			+ "Its CSRF token remains valid.")
	@Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
			description = "Token from GET /api/v1/auth/csrf", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "204", description = "Password changed; session ID rotated and other sessions revoked", content = @Content,
			headers = @Header(name = "Set-Cookie", description = "New SESSION cookie; HttpOnly, Secure, SameSite=Lax", schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "400", description = "Invalid request or incorrect current password",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Authentication required, account disabled or no longer exists",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Password changed concurrently; retry with current credentials",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "Client IP rate limit exceeded; retry after the indicated seconds",
			headers = @Header(name = "Retry-After", schema = @Schema(type = "integer", format = "int64", minimum = "1")),
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	void changePassword(Principal principal, @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
		passwords.change(UUID.fromString(principal.getName()), body.currentPassword(), body.newPassword(),
				request.getSession(false).getId());
		// Re-authentication rotates the session ID like login does. Attributes,
		// including the CSRF token, move to the new ID.
		request.changeSessionId();
	}

	record ChangePasswordRequest(
			@NotBlank @Size(min = 1, max = 72) @BcryptPassword @Schema(format = "password", description = "At most 72 bytes of UTF-8") String currentPassword,
			@NotBlank @Size(min = 1, max = 72) @BcryptPassword @Schema(format = "password", description = "Non-blank password, at most 72 bytes of UTF-8") String newPassword) {
	}

}
