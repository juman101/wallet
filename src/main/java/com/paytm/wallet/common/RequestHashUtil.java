package com.paytm.wallet.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint of a transfer request's semantic fields, used to detect
 * "same idempotency_key, different body" (must be a 409) vs a genuine retry. We hash the
 * normalized fields rather than comparing raw JSON, since raw JSON comparison is brittle to
 * field ordering / whitespace.
 */
public final class RequestHashUtil {

    private RequestHashUtil() {
    }

    public static String sha256(String... fields) {
        String canonical = String.join("|", fields);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
