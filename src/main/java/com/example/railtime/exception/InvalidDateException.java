package com.example.railtime.exception;

/** A syntactically valid date that the booking rules cannot accept (for example, out of range). */
public class InvalidDateException extends RuntimeException {

    public InvalidDateException(String message) {
        super(message);
    }
}
