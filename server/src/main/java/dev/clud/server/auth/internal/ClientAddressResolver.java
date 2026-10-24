package dev.clud.server.auth.internal;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
class ClientAddressResolver {

	private final List<IpAddressMatcher> trustedProxies;

	ClientAddressResolver(AuthRateLimitProperties properties) {
		trustedProxies = properties.trustedProxies().stream().filter(value -> !value.isBlank())
				.map(value -> {
					// Require literal addresses/CIDRs, so configuration cannot trigger DNS
					// lookups and malformed trusted proxy entries fail at startup.
					InetAddress.ofLiteral(value.split("/", 2)[0]);
					return new IpAddressMatcher(value);
				}).toList();
	}

	/** Returns the client's rate-limit key: its IPv4 address or its IPv6 /64 prefix. */
	String resolve(HttpServletRequest request) {
		InetAddress remote = literal(request.getRemoteAddr());
		if (remote == null) {
			return "unknown";
		}
		if (trustedProxies.stream().anyMatch(proxy -> proxy.matches(remote.getHostAddress()))) {
			var headers = request.getHeaders("X-Forwarded-For");
			if (headers.hasMoreElements()) {
				InetAddress forwarded = literal(headers.nextElement().strip());
				// The edge proxy must overwrite X-Forwarded-For with one IP. Reject
				// chains and duplicates rather than guessing which address to trust.
				if (forwarded != null && !headers.hasMoreElements()) {
					return key(forwarded);
				}
			}
		}
		return key(remote);
	}

	// An IPv6 client usually controls a whole /64. Keying full addresses would let
	// it rotate through them to escape its limit and fill the limiter's table.
	private static String key(InetAddress address) {
		if (!(address instanceof Inet6Address)) {
			return address.getHostAddress();
		}
		byte[] prefix = address.getAddress();
		Arrays.fill(prefix, 8, 16, (byte) 0);
		try {
			return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException("IPv6 address must have 16 bytes", ex);
		}
	}

	private static InetAddress literal(String value) {
		try {
			return value == null ? null : InetAddress.ofLiteral(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
