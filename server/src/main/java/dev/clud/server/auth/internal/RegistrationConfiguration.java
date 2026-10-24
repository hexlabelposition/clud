package dev.clud.server.auth.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.clud.server.common.CludProperties;
import dev.clud.server.common.RegistrationMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
class RegistrationConfiguration implements WebMvcConfigurer {

	private final CludProperties properties;

	RegistrationConfiguration(CludProperties properties) {
		this.properties = properties;
	}

	// Interceptors run before the request body is read and validated, so a closed
	// instance answers 403 instead of reporting errors in a request it would refuse
	// anyway. Invite mode needs the body: the invitation token travels in it.
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {

			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				if (properties.registrationMode() == RegistrationMode.CLOSED) {
					throw RegistrationController.registrationClosed();
				}
				return true;
			}

		}).addPathPatterns("/api/v1/auth/register");
	}

}
