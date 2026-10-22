package dev.clud.server.common.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 Problem Details ({@code application/problem+json}).
 * <p>
 * Spring MVC exceptions and {@link org.springframework.web.ErrorResponseException}
 * are handled by {@link ResponseEntityExceptionHandler}. Security exceptions are
 * routed here from the security filter chain.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler
	ProblemDetail handleAuthentication(AuthenticationException ex) {
		return ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler
	ProblemDetail handleAccessDenied(AccessDeniedException ex) {
		return ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
	}

	// Anything unhandled is a bug: log it and keep the details away from the client.
	@ExceptionHandler
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
	}

}
