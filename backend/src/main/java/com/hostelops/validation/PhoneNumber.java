package com.hostelops.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An Indian ten-digit mobile number.
 *
 * <p>A plain {@code @Pattern} on each DTO field would work and was rejected: the
 * rule would then be written out at every use site, and the twelfth copy is where
 * it diverges. This annotation is also the same rule the database enforces
 * ({@code ck_students_mobile}), so a value that passes validation cannot fail on
 * insert -- and a value written by some path that skips validation still cannot get
 * in.
 *
 * <p>Deliberately permissive about presentation and strict about content: spaces,
 * dashes and a {@code +91} country code are stripped before checking, because
 * rejecting "98765 43210" teaches a user to distrust the form rather than teaching
 * them the format.
 */
@Documented
@Constraint(validatedBy = PhoneNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PhoneNumber {

    String message() default "Must be a 10-digit mobile number starting 6-9";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
