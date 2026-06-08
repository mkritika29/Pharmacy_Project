package com.crescent.pharmacy.idempotency.exception;

public class IdempotencyKeyNotFoundException extends RuntimeException {

    public IdempotencyKeyNotFoundException(String message) {
        super(message);
    }
}
