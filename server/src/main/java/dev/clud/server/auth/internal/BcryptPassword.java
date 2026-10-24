package dev.clud.server.auth.internal;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * The password fits bcrypt, which rejects more than 72 bytes of UTF-8. Every
 * request that accepts a password to hash or check validates this, so long
 * passwords fail validation instead of the encoder. {@code null} is valid.
 */
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
@Constraint(validatedBy = BcryptPassword.Validator.class)
@interface BcryptPassword {

	int MAX_BYTES = 72;

	String message() default "must not exceed 72 bytes of UTF-8";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<BcryptPassword, String> {

		@Override
		public boolean isValid(String password, ConstraintValidatorContext context) {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
		}

	}

}
