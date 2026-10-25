package dev.clud.server.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTests {

	private final ClientAddressResolver resolver = new ClientAddressResolver(
			new AuthRateLimitProperties(10, 5, 5, 60, 60, 100, List.of("10.0.0.0/8", "::1/128")));

	@Test
	void ignoresSpoofedHeadersFromUntrustedPeer() {
		var request = request("192.0.2.1", "198.51.100.1");
		request.addHeader("Forwarded", "for=198.51.100.2");
		request.addHeader("X-Real-IP", "198.51.100.3");
		assertThat(resolver.resolve(request)).isEqualTo("192.0.2.1");
	}

	@Test
	void trustsNoProxyByDefault() {
		var untrusted = new ClientAddressResolver(new AuthRateLimitProperties(10, 5, 5, 60, 60, 100, List.of()));
		assertThat(untrusted.resolve(request("10.0.0.1", "192.0.2.1"))).isEqualTo("10.0.0.1");
	}

	@Test
	void acceptsSingleLiteralFromTrustedIpv4OrIpv6Peer() {
		assertThat(resolver.resolve(request("10.0.0.1", "192.0.2.1"))).isEqualTo("192.0.2.1");
		assertThat(resolver.resolve(request("::1", "192.0.2.2"))).isEqualTo("192.0.2.2");
		assertThat(resolver.resolve(request("10.0.0.1", "2001:db8::1")))
				.isEqualTo(resolver.resolve(request("10.0.0.1", "2001:0db8:0:0:0:0:0:1")));
	}

	@Test
	void groupsIpv6ClientsByTheirSlash64() {
		var untrusted = new ClientAddressResolver(new AuthRateLimitProperties(10, 5, 5, 60, 60, 100, List.of()));
		String key = untrusted.resolve(request("2001:db8:1:2::1", null));
		assertThat(key).isEqualTo("2001:db8:1:2:0:0:0:0/64");
		assertThat(untrusted.resolve(request("2001:db8:1:2:ffff:ffff:ffff:ffff", null))).isEqualTo(key);
		assertThat(untrusted.resolve(request("2001:db8:1:3::1", null))).isNotEqualTo(key);
		assertThat(resolver.resolve(request("10.0.0.1", "2001:db8:1:2:abcd::5"))).isEqualTo(key);
		assertThat(untrusted.resolve(request("::ffff:192.0.2.1", null))).isEqualTo("192.0.2.1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "not-an-ip", "example.com", "192.0.2.1, 192.0.2.2", "unknown" })
	void fallsBackToPeerForInvalidForwardedValue(String value) {
		assertThat(resolver.resolve(request("10.0.0.1", value))).isEqualTo("10.0.0.1");
	}

	@Test
	void fallsBackForMissingAndDuplicateHeaders() {
		var request = request("10.0.0.1", "192.0.2.1");
		request.addHeader("X-Forwarded-For", "192.0.2.2");
		assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
		request.removeHeader("X-Forwarded-For");
		assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
	}

	private MockHttpServletRequest request(String peer, String forwarded) {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr(peer);
		if (forwarded != null) {
			request.addHeader("X-Forwarded-For", forwarded);
		}
		return request;
	}

}
