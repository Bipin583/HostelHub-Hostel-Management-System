package com.hostelops.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phone number normalisation and validation.
 *
 * <p>These two behaviours are asymmetric on purpose: the validator is generous
 * about how a number is typed, and the normaliser is strict about how it is stored.
 * Being generous in both places is what puts "+91 98765-43210" and "9876543210" in
 * one column as two different-looking numbers; being strict in both is what makes a
 * form reject a number a human considers correct.
 */
class PhoneNumberValidatorTest {

    private final PhoneNumberValidator validator = new PhoneNumberValidator();

    @Nested
    @DisplayName("normalising")
    class Normalising {

        @ParameterizedTest(name = "\"{0}\" -> 9876543210")
        @ValueSource(strings = {
                "9876543210",
                "98765 43210",
                "98765-43210",
                "98765.43210",
                "(98765) 43210",
                "  9876543210  ",
                "+919876543210",
                "+91 98765 43210",
                "+91-98765-43210",
                "00919876543210",
                "0091 98765 43210",
                "09876543210"})
        @DisplayName("every spelling of one number collapses to the same ten digits")
        void collapsesToTenDigits(String spelling) {
            assertThat(PhoneNumberValidator.normalise(spelling)).isEqualTo("9876543210");
        }

        @Test
        @DisplayName("null stays null rather than becoming an empty string")
        void nullIsPreserved() {
            // The service writes this straight into a nullable column, so turning null
            // into "" here would store a value the CHECK constraint rejects in a field
            // the schema says is optional.
            assertThat(PhoneNumberValidator.normalise(null)).isNull();
        }

        @Test
        @DisplayName("a leading zero is only stripped when it makes the length right")
        void leadingZeroIsNotStrippedBlindly() {
            // "0987654321" is already ten characters. Stripping its zero would produce
            // a nine-digit number that then fails validation for the wrong reason, and
            // would quietly corrupt anything that did get through.
            assertThat(PhoneNumberValidator.normalise("0987654321")).isEqualTo("0987654321");
        }
    }

    @Nested
    @DisplayName("validating")
    class Validating {

        @ParameterizedTest
        @ValueSource(strings = {
                "9876543210",
                "8876543210",
                "7876543210",
                "6876543210",
                "+91 98765-43210",
                "(0091) 91234 56789",
                "09876543210"})
        @DisplayName("accepts a well-formed Indian mobile however it was typed")
        void acceptsWellFormedNumbers(String value) {
            assertThat(validator.isValid(value, null)).isTrue();
        }

        @ParameterizedTest(name = "rejects \"{0}\": {1}")
        @CsvSource(delimiter = '|', value = {
                "5876543210  | landline prefixes are not mobile numbers",
                "0876543210  | ten digits starting with 0 is not a mobile number",
                "1234567890  | starts below the mobile range",
                "987654321   | nine digits",
                "98765432100 | eleven digits with no country code to strip",
                "abcdefghij  | letters are not stripped, so they fail the pattern",
                "'   '       | whitespace alone normalises to empty",
                "''          | empty is not a number"})
        @DisplayName("rejects what is not a mobile number")
        void rejectsMalformedNumbers(String value, String why) {
            assertThat(validator.isValid(value, null)).as(why).isFalse();
        }

        @Test
        @DisplayName("null passes, because optional-but-well-formed is a real requirement")
        void nullPasses() {
            // A parent's number is optional. If this validator also enforced presence,
            // the only way to express "optional" would be to drop the annotation -- and
            // then a present-but-malformed value would sail through unchecked.
            // @NotBlank answers presence; this answers shape.
            assertThat(validator.isValid(null, null)).isTrue();
        }
    }
}
