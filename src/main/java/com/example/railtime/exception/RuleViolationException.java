package com.example.railtime.exception;

/**
 * A well-formed request that a booking rule does not allow (for example RAC on a train that has
 * no RAC). Reported as {@code VALIDATION_ERROR} against the named field.
 */
public class RuleViolationException extends RuntimeException {

    private final String field;

    public RuleViolationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
