package dev.clud.server.auth.internal;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.RequestPath;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import dev.clud.server.auth.internal.AuthRateLimiter.Endpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Constructed only in the security chain, not registered as a servlet filter.
class AuthRateLimitFilter extends OncePerRequestFilter {

	static final String RATE_LIMIT_EXCEEDED = "urn:clud:problem:rate-limit-exceeded";

	private static final PathPattern LOGIN = PathPatternParser.defaultInstance.parse("/api/v1/auth/login");

	private static final PathPattern REGISTER = PathPatternParser.defaultInstance.parse("/api/v1/auth/register");

	private static final PathPattern PASSWORD = PathPatternParser.defaultInstance.parse("/api/v1/auth/password");

	private static final PathPattern CSRF = PathPatternParser.defaultInstance.parse("/api/v1/auth/csrf");

	private final AuthRateLimiter limiter;

	private final ClientAddressResolver addresses;

	private final HandlerExceptionResolver exceptions;

	AuthRateLimitFilter(AuthRateLimiter limiter, ClientAddressResolver addresses, HandlerExceptionResolver exceptions) {
		this.limiter = limiter;
		this.addresses = addresses;
		this.exceptions = exceptions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Endpoint endpoint = endpoint(request);
		long retryAfter = endpoint == null ? 0 : limiter.acquire(endpoint, addresses.resolve(request));
		if (retryAfter > 0) {
			var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later");
			problem.setType(URI.create(RATE_LIMIT_EXCEEDED));
			var error = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
			error.getHeaders().set("Retry-After", Long.toString(retryAfter));
			exceptions.resolveException(request, response, null, error);
			return;
		}
		chain.doFilter(request, response);
	}

	private static Endpoint endpoint(HttpServletRequest request) {
		var path = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
		if (request.getMethod().equals("POST")) {
			if (LOGIN.matches(path)) {
				return Endpoint.LOGIN;
			}
			if (REGISTER.matches(path)) {
				return Endpoint.REGISTER;
			}
			if (PASSWORD.matches(path)) {
				return Endpoint.PASSWORD;
			}
		}
		if ((request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) && CSRF.matches(path)) {
			return Endpoint.CSRF;
		}
		return null;
	}

}
