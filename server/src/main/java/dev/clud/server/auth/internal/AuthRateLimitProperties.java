package dev.clud.server.auth.internal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties("clud.auth.rate-limit")
record AuthRateLimitProperties(
		@DefaultValue("10") @Min(1) int loginRequests,
		@DefaultValue("5") @Min(1) int registerRequests,
		@DefaultValue("5") @Min(1) int passwordRequests,
		@DefaultValue("60") @Min(1) int csrfRequests,
		@DefaultValue("60") @Min(1) @Max(86400) int windowSeconds,
		@DefaultValue("10000") @Min(1) @Max(1000000) int maxBuckets,
		List<String> trustedProxies) {

	AuthRateLimitProperties {
		trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
	}

}
