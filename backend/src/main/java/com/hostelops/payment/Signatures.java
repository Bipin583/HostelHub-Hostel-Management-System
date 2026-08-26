package com.hostelops.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over {@code orderId|paymentId}, hex encoded.
 *
 * <p>That payload format is Razorpay's, and the mock adapter uses the same one on
 * purpose: the signing and verification code under test is then the code that runs in
 * production, differing only in which secret it holds. A mock that skipped
 * verification, or checked something simpler, would leave the real path exercised for
 * the first time by a real payment.
 */
final class Signatures {

    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Signatures() {
    }

    static String sign(String secret, String orderId, String paymentId) {
        byte[] mac = mac(secret, orderId + "|" + paymentId);
        StringBuilder hex = new StringBuilder(mac.length * 2);
        for (byte b : mac) {
            hex.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return hex.toString();
    }

    /**
     * Compares a presented signature against the expected one.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}, because
     * {@code equals} returns as soon as two characters differ and so takes measurably
     * longer the more of a guess is correct. That is a timing oracle: given enough
     * attempts it lets an attacker recover a valid signature a character at a time.
     * Comparing in constant time costs nothing here and removes the question.
     *
     * <p>The comparison is on raw bytes, so a signature differing only in hex case
     * fails. Providers send lower case, and quietly accepting either would mean this
     * function no longer knows exactly what it is verifying.
     */
    static boolean matches(String secret, String orderId, String paymentId, String presented) {
        if (presented == null) {
            return false;
        }
        String expected = sign(secret, orderId, paymentId);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] mac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // HmacSHA256 is required of every JVM. Unreachable, and not something a
            // caller could do anything about, so it is not on any signature.
            throw new IllegalStateException("HmacSHA256 is unavailable in this JVM", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("The configured signing secret is not a usable HMAC key", e);
        }
    }
}
