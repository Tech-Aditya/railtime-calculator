package com.example.railtime.exception;

import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.properties.ApplicationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates every API failure into the standard {@link ErrorResponse}: a stable machine-readable
 * {@code error} code, a message that says what is wrong and how to fix it, the offending fields in
 * {@code details}, and a {@code requestId} that also appears in the log line. Stack traces are
 * logged server-side only and never returned to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String MSG_MISSING_BODY = "Request body is missing. Send a JSON object with the required fields.";
    static final String MSG_MALFORMED = "Request body is not valid JSON";
    static final String MSG_NOT_AN_OBJECT = "Request body must be a JSON object, for example {\"originDepartureDate\":\"2026-12-20\"}";
    static final String MSG_BAD_DATE_FORMAT = "Dates must be valid calendar dates in ISO-8601 format (yyyy-MM-dd)";
    static final String MSG_BAD_TIME_FORMAT = "Times must be valid 24-hour times (HH:mm)";
    static final String MSG_INTERNAL = "Something went wrong on our side. Please try again later.";
    static final String MSG_NOT_FOUND = "No API endpoint exists at this path";
    static final String MSG_NOTHING_HERE = "Nothing exists at this path";
    static final String MSG_NOT_ACCEPTABLE = "This API only returns JSON. Send 'Accept: application/json' or omit the Accept header.";

    private final ErrorResponseFactory errors;
    private final JsonMapper jsonMapper;
    private final boolean includeExceptionDetail;

    public GlobalExceptionHandler(ErrorResponseFactory errors, JsonMapper jsonMapper, ApplicationProperties properties) {
        this.errors = errors;
        this.jsonMapper = jsonMapper;
        this.includeExceptionDetail = properties.errors().includeExceptionDetail();
    }

    // ------------------------------------------------------------------ 400: validation

    /** Bean-validation failures: every invalid field is listed in {@code details}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(fe -> new ErrorResponse.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return respond(ApiErrorCode.VALIDATION_ERROR, summarize(violations), request, violations);
    }

    /** "Fare is required" for one problem; "Fare is required (and 2 more problems, see details)" for several. */
    static String summarize(List<ErrorResponse.FieldViolation> violations) {
        if (violations.isEmpty()) {
            return "The request contains invalid values";
        }
        String first = violations.getFirst().message();
        int more = violations.size() - 1;
        return more == 0 ? first : "%s (and %d more %s, see details)".formatted(first, more, more == 1 ? "problem" : "problems");
    }

    /** Unreadable bodies: missing, not JSON, wrong shape, or a field of the wrong type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidTravelClassException itc) {
                return respond(ApiErrorCode.INVALID_TRAVEL_CLASS, itc.getMessage(), request, List.of());
            }
            if (cause instanceof MismatchedInputException mie) {
                return mismatch(mie, request);
            }
            if (cause instanceof DateTimeException) {
                return respond(ApiErrorCode.INVALID_DATE, MSG_BAD_DATE_FORMAT, request, List.of());
            }
            if (cause instanceof StreamReadException sre) {
                return respond(ApiErrorCode.MALFORMED_REQUEST, malformedMessage(sre), request, List.of());
            }
        }
        boolean empty = ex.getMessage() != null && ex.getMessage().startsWith("Required request body is missing");
        return respond(ApiErrorCode.MALFORMED_REQUEST, empty ? MSG_MISSING_BODY : MSG_MALFORMED, request, List.of());
    }

    /** A JSON value that can't become the target type: says which field and what is expected. */
    private ResponseEntity<ErrorResponse> mismatch(MismatchedInputException mie, HttpServletRequest request) {
        Class<?> target = mie.getTargetType();
        String field = fieldPath(mie);
        if (field.isEmpty()) {
            // The whole body has the wrong shape (e.g. an array or a string instead of an object).
            return respond(ApiErrorCode.MALFORMED_REQUEST, MSG_NOT_AN_OBJECT, request, List.of());
        }
        if (target == LocalTime.class) {
            return fieldError(ApiErrorCode.INVALID_DATE, field, MSG_BAD_TIME_FORMAT, request);
        }
        if (target != null && (target == LocalDate.class || Temporal.class.isAssignableFrom(target))) {
            return fieldError(ApiErrorCode.INVALID_DATE, field, MSG_BAD_DATE_FORMAT, request);
        }
        if (target != null && target.isEnum()) {
            return fieldError(ApiErrorCode.VALIDATION_ERROR, field,
                    "Invalid value for %s. Allowed values: %s".formatted(field, allowedValues(target)), request);
        }
        if (target != null && isNumber(target)) {
            String kind = isWholeNumber(target) ? "a whole number" : "a number";
            return fieldError(ApiErrorCode.VALIDATION_ERROR, field, "%s must be %s".formatted(field, kind), request);
        }
        if (target == String.class) {
            return fieldError(ApiErrorCode.VALIDATION_ERROR, field, "%s must be text".formatted(field), request);
        }
        return fieldError(ApiErrorCode.VALIDATION_ERROR, field, "%s has a value of the wrong type".formatted(field), request);
    }

    private ResponseEntity<ErrorResponse> fieldError(ApiErrorCode code, String field, String message,
                                                     HttpServletRequest request) {
        return respond(code, message, request, List.of(new ErrorResponse.FieldViolation(field, message)));
    }

    /** "passengers" or "details[0].name" from Jackson's reference path; empty for the root value. */
    static String fieldPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String malformedMessage(StreamReadException ex) {
        var location = ex.getLocation();
        if (location == null || location.getLineNr() < 1) {
            return MSG_MALFORMED;
        }
        return "%s (problem at line %d, column %d)".formatted(MSG_MALFORMED, location.getLineNr(), location.getColumnNr());
    }

    private static boolean isNumber(Class<?> type) {
        return Number.class.isAssignableFrom(type) || type == int.class || type == long.class
                || type == double.class || type == float.class || type == short.class || type == byte.class;
    }

    private static boolean isWholeNumber(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || type == Integer.class || type == Long.class || type == Short.class || type == Byte.class;
    }

    private static String allowedValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ 400: business rules

    @ExceptionHandler(InvalidDateException.class)
    ResponseEntity<ErrorResponse> handleInvalidDate(InvalidDateException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.INVALID_DATE, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(RuleViolationException.class)
    ResponseEntity<ErrorResponse> handleRuleViolation(RuleViolationException ex, HttpServletRequest request) {
        return fieldError(ApiErrorCode.VALIDATION_ERROR, ex.field(), ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTravelClassException.class)
    ResponseEntity<ErrorResponse> handleInvalidClass(InvalidTravelClassException ex, HttpServletRequest request) {
        return respond(ApiErrorCode.INVALID_TRAVEL_CLASS, ex.getMessage(), request, List.of());
    }

    // ------------------------------------------------------------------ 4xx: protocol

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        String sent = ex.getContentType() == null ? "(none)" : "'" + ex.getContentType() + "'";
        return respond(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type %s is not supported. Send JSON with 'Content-Type: application/json'.".formatted(sent),
                request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String allowed = ex.getSupportedHttpMethods() == null ? ""
                : ex.getSupportedHttpMethods().stream().map(Object::toString).sorted().collect(Collectors.joining(", "));
        String message = allowed.isEmpty()
                ? "HTTP method %s is not supported for this endpoint".formatted(ex.getMethod())
                : "HTTP method %s is not supported for this endpoint. Use %s.".formatted(ex.getMethod(), allowed);
        ResponseEntity<ErrorResponse> response = respond(ApiErrorCode.METHOD_NOT_ALLOWED, message, request, List.of());
        return allowed.isEmpty() ? response
                : ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.ALLOW, allowed).body(response.getBody());
    }

    /**
     * The client does not accept JSON. The body is written directly, because content negotiation
     * would reject a JSON error body for the same reason.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    void handleNotAcceptable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorResponse body = errors.create(ApiErrorCode.NOT_ACCEPTABLE, MSG_NOT_ACCEPTABLE, request.getRequestURI());
        logRejection(body);
        response.setStatus(ApiErrorCode.NOT_ACCEPTABLE.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        boolean api = request.getRequestURI().startsWith(request.getContextPath() + "/api/");
        return respond(ApiErrorCode.NOT_FOUND, api ? MSG_NOT_FOUND : MSG_NOTHING_HERE, request, List.of());
    }

    /** The client went away (closed tab, timeout): nothing can be sent, so only note it. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("event=client_disconnected path={}", request.getRequestURI());
    }

    // ------------------------------------------------------------------ 500

    /** Anything unexpected: a generic message plus a reference that matches the logged stack trace. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = errors.newRequestId();
        log.error("event=unhandled_exception requestId={} method={} path={}", requestId, request.getMethod(),
                request.getRequestURI(), ex);
        String message = "%s If it keeps happening, quote reference %s.".formatted(MSG_INTERNAL, requestId);
        if (includeExceptionDetail) {
            message += " [" + ex.getClass().getSimpleName() + "]";
        }
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.status())
                .body(errors.create(ApiErrorCode.INTERNAL_ERROR, message, request.getRequestURI(), List.of(), requestId));
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<ErrorResponse> respond(ApiErrorCode code, String message, HttpServletRequest request,
                                                  List<ErrorResponse.FieldViolation> details) {
        ErrorResponse body = errors.create(code, message, request.getRequestURI(), details);
        logRejection(body);
        return ResponseEntity.status(code.status()).body(body);
    }

    private static void logRejection(ErrorResponse body) {
        log.info("event=request_rejected requestId={} path={} error={} message=\"{}\"",
                body.requestId(), body.path(), body.error(), body.message());
    }
}
