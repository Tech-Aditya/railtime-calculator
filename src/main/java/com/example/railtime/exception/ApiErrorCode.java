package com.example.railtime.exception;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes returned in {@code ErrorResponse.error}. */
public enum ApiErrorCode {
    /** A field is missing, out of range or of the wrong type. */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    /** A date or time is not a real value, or is outside the supported window. */
    INVALID_DATE(HttpStatus.BAD_REQUEST),
    /** The travel class code is unknown. */
    INVALID_TRAVEL_CLASS(HttpStatus.BAD_REQUEST),
    /** The body is missing, not JSON, or not a JSON object. */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    /** The client asked (Accept header) for a format other than JSON. */
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
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
