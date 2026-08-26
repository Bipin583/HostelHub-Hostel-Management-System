package com.hostelops.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Implements {@link PhoneNumber}.
 *
 * <p>Null passes. Whether a number is required is a separate question, answered by
 * {@code @NotBlank} on the fields that need it -- a validator that enforces both
 * cannot express "optional, but well-formed if present", which is exactly what a
 * parent's number is.
 */
public class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {

    /** The same shape as {@code ck_students_mobile} in {@code V1__init.sql}. */
    private static final Pattern TEN_DIGITS = Pattern.compile("^[6-9][0-9]{9}$");

    private static final Pattern PRESENTATION = Pattern.compile("[\\s()\\-.]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return TEN_DIGITS.matcher(normalise(value)).matches();
    }

    /**
     * Strips formatting and a country code so the pattern sees digits only.
     *
     * <p>Public because the service layer normalises with this before storing:
     * accepting "+91 98765-43210" and then writing it verbatim would put two
     * spellings of one number in the column and break every lookup by number.
     */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String digits = PRESENTATION.matcher(value.trim()).replaceAll("");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0091")) {
            digits = digits.substring(4);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }
}
