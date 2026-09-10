package com.paytm.wallet.common.exceptions;

/**
 * Same idempotency_key reused with a different request body. Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency_key '" + idempotencyKey + "' was already used with a different request body");
    }
}
