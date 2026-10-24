package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import dev.clud.server.common.CludProperties;
import dev.clud.server.common.RegistrationMode;

class RegistrationPolicyTests {

	@ParameterizedTest
	@EnumSource(value = RegistrationMode.class, names = { "INVITE", "CLOSED" })
	void rejectsRegistrationBeforeHashingOrCreatingAccount(RegistrationMode mode) {
		var registration = mock(RegistrationService.class);
		var controller = new RegistrationController(new CludProperties(URI.create("http://localhost"), mode),
				registration);

		assertThatThrownBy(() -> controller.register(new RegistrationController.RegistrationRequest("Alice", null, "password", null)))
				.isInstanceOfSatisfying(ErrorResponseException.class, error -> {
					assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(error.getBody().getType()).hasToString(mode == RegistrationMode.CLOSED
							? RegistrationController.REGISTRATION_CLOSED : RegistrationController.INVITATION_REQUIRED);
				});
		verifyNoInteractions(registration);
	}

}
