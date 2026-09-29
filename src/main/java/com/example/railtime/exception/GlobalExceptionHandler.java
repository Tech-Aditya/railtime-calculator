package com.example.railtime.exception;

import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.properties.ApplicationProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Translates every API failure into the standard {@link ErrorResponse}. Stack traces are logged
 * server-side only and never returned to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String MSG_MALFORMED = "Request body is missing or is not valid JSON";
    static final String MSG_BAD_DATE_FORMAT = "Dates must be valid calendar dates in ISO-8601 format (yyyy-MM-dd)";
    static final String MSG_BAD_TIME_FORMAT = "Times must be valid 24-hour times (HH:mm)";
    static final String MSG_INTERNAL ="An unexpected error occurred. Please try again later.";
    static final String MSG_NOT_FOUND = "The requested resource was not found";

    private final ErrorResponseFactory errors;
    private final boolean includeExceptionDetail;

    public GlobalExceptionHandler(ErrorResponseFactory errors, ApplicationProperties properties) {
        this.errors = errors;
        this.includeExceptionDetail = properties.errors().includeExceptionDetail();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(fe -> new ErrorResponse.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        String message = violations.isEmpty() ? "Request validation failed" : violations.getFirst().message();
        return respond(ApiErrorCode.VALIDATION_ERROR, message, request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidTravelClassException itc) {
                return respond(ApiErrorCode.INVALID_TRAVEL_CLASS, itc.getMessage(), request, List.of());
            }
            if (cause instanceof MismatchedInputException mie && mie.getTargetType() == LocalTime.class) {
                return respond(ApiErrorCode.INVALID_DATE, MSG_BAD_TIME_FORMAT, request, List.of());
            }
            if (cause instanceof DateTimeException || isTemporalMismatch(cause)) {
                return respond(ApiErrorCode.INVALID_DATE, MSG_BAD_DATE_FORMAT, request, List.of());
            }
            if (cause instanceof MismatchedInputException mie && mie.getTargetType() != null
                    && mie.getTargetType().isEnum()) {
                return respond(ApiErrorCode.VALIDATION_ERROR,
                        "Invalid value. Allowed values: " + allowedValues(mie.getTargetType()), request, List.of());
            }
        }
        return respond(ApiErrorCode.MALFORMED_REQUEST, MSG_MALFORMED, request, List.of());
    }

    @ExceptionHandler(InvalidDateException.class)
    ResponseEntity<ErrorResponse> handleInvalidDate(InvalidDateException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.INVALID_DATE, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidTravelClassException.class)
    ResponseEntity<ErrorResponse> handleInvalidClass(InvalidTravelClassException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.INVALID_TRAVEL_CLASS, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.NOT_FOUND, MSG_NOT_FOUND, request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("event=unhandled_exception path={}", request.getRequestURI(), ex);
        String message = includeExceptionDetail ? MSG_INTERNAL + " [" + ex.getClass().getSimpleName() + "]" : MSG_INTERNAL;
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.status())
                .body(errors.create(ApiErrorCode.INTERNAL_ERROR, message, request.getRequestURI()));
    }

    private ResponseEntity<ErrorResponse> respond(ApiErrorCode code, String message, HttpServletRequest request,
                                                  List<ErrorResponse.FieldViolation> details) {
        log.info("event=request_rejected path={} error={} message=\"{}\"", request.getRequestURI(), code, message);
        return ResponseEntity.status(code.status()).body(errors.create(code, message, request.getRequestURI(), details));
    }

    private static String allowedValues(Class<?> enumType) {
        return java.util.Arrays.stream(enumType.getEnumConstants()).map(Object::toString)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean isTemporalMismatch(Throwable cause) {
        return cause instanceof MismatchedInputException mie
                && mie.getTargetType() != null
                && Temporal.class.isAssignableFrom(mie.getTargetType());
    }
}
