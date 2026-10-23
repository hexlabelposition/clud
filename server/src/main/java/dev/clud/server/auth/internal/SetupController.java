package dev.clud.server.auth.internal;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.clud.server.users.FirstRunAccounts;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Tag(name = "Setup")
@RestController
@RequestMapping("/api/v1/setup")
class SetupController {

	private final FirstRunAccounts accounts;

	private final PasswordEncoder passwords;

	SetupController(FirstRunAccounts accounts, PasswordEncoder passwords) {
		this.accounts = accounts;
		this.passwords = passwords;
	}

	@GetMapping
	SetupStatus setupStatus() {
		return new SetupStatus(accounts.isSetupNeeded());
	}

	@PostMapping(consumes = "application/json")
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "201", description = "Administrator created; log in to start a session", content = @Content)
	@ApiResponse(responseCode = "400", description = "Invalid account details",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Setup has already been completed",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	void setup(@Valid @RequestBody SetupRequest request) {
		// Avoid hashing on instances that have already been configured. The atomic
		// check in createAdmin remains necessary for concurrent setup requests.
		if (!accounts.isSetupNeeded()
				|| !accounts.createAdmin(request.username(), request.email(), passwords.encode(request.password()))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Setup has already been completed");
		}
	}

	record SetupStatus(@Schema(requiredMode = RequiredMode.REQUIRED) boolean setupNeeded) {
	}

	record SetupRequest(
			@NotNull @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
			@Email @Size(max = 254) String email,
			@NotBlank @Size(min = 1, max = 72) @Schema(description = "Non-blank password, at most 72 bytes of UTF-8",
					format = "password") String password) {

		@AssertTrue(message = "Password must not exceed 72 bytes of UTF-8")
		@Schema(hidden = true)
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}

	}

}
