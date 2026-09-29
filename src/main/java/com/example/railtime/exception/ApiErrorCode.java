package com.example.railtime.exception;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes returned in {@code ErrorResponse.error}. */
public enum ApiErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_DATE(HttpStatus.BAD_REQUEST),
    INVALID_TRAVEL_CLASS(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ApiErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
