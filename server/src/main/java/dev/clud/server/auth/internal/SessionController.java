package dev.clud.server.auth.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
class SessionController {

	private final AuthenticationManager authenticationManager;

	private final SessionAuthenticationStrategy sessions;

	private final SecurityContextRepository contexts;

	private final LogoutHandler logoutHandler;

	SessionController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessions,
			SecurityContextRepository contexts, LogoutHandler logoutHandler) {
		this.authenticationManager = authenticationManager;
		this.sessions = sessions;
		this.contexts = contexts;
		this.logoutHandler = logoutHandler;
	}

	@GetMapping("/csrf")
	@Operation(description = "Returns a CSRF token and its header name, creating an anonymous session if needed. "
			+ "Keep the SESSION cookie and send the token on login and logout. Fetch a fresh token after either operation.")
	CsrfResponse csrfToken(@Parameter(hidden = true) CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@PostMapping(value = "/login", consumes = "application/json")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
			description = "Token from GET /api/v1/auth/csrf", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "204", description = "Logged in; session cookie issued", content = @Content,
			headers = @Header(name = "Set-Cookie", description = "SESSION cookie; HttpOnly, Secure, SameSite=Lax", schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "400", description = "Invalid login request",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials or disabled account",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	void login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		var authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
		sessions.onAuthentication(authentication, request, response);
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
			description = "Token from GET /api/v1/auth/csrf", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "204", description = "Logged out; session invalidated", content = @Content)
	@ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	void logout(HttpServletRequest request, HttpServletResponse response) {
		logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
	}

	record CsrfResponse(@Schema(requiredMode = RequiredMode.REQUIRED) String headerName,
			@Schema(requiredMode = RequiredMode.REQUIRED) String token) {
	}

	record LoginRequest(
			@NotBlank @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
			@NotBlank @Size(min = 1, max = 72) @BcryptPassword @Schema(format = "password", description = "At most 72 bytes of UTF-8") String password) {
	}

}
