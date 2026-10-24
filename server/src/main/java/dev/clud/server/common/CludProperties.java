package dev.clud.server.common;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instance configuration, set through {@code CLUD_*} environment variables.
 *
 * @param publicUrl the URL users reach the instance at
 *                  ({@code CLUD_PUBLIC_URL})
 * @param registrationMode registration policy ({@code CLUD_REGISTRATION_MODE}); defaults to invite
 */
@ConfigurationProperties("clud")
public record CludProperties(URI publicUrl, @DefaultValue("invite") RegistrationMode registrationMode) {
}
