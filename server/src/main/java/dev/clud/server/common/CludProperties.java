package dev.clud.server.common;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Instance configuration, set through {@code CLUD_*} environment variables.
 *
 * @param publicUrl the URL users reach the instance at
 *                  ({@code CLUD_PUBLIC_URL})
 */
@ConfigurationProperties("clud")
public record CludProperties(URI publicUrl) {
}
