package com.example.railtime.exception;

import com.example.railtime.config.RequestLoggingFilter;
import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.properties.BookingRulesProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Builds {@link ErrorResponse} bodies with an IST timestamp and a short reference id; shared by
 * the MVC and security error paths.
 */
@Component
public class ErrorResponseFactory {

    private final Clock clock;
    private final BookingRulesProperties properties;

    public ErrorResponseFactory(Clock clock, BookingRulesProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * The reference that correlates a response with the log: the current request's id (see
     * {@link RequestLoggingFilter}), or a short random one outside a request.
     */
    public String newRequestId() {
        String current = RequestLoggingFilter.currentRequestId();
        return current != null ? current : HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    public ErrorResponse create(ApiErrorCode code, String message, String path) {
        return create(code, message, path, List.of(), newRequestId());
    }

    public ErrorResponse create(ApiErrorCode code, String message, String path,
                                List<ErrorResponse.FieldViolation> details) {
        return create(code, message, path, details, newRequestId());
    }

    public ErrorResponse create(ApiErrorCode code, String message, String path,
                                List<ErrorResponse.FieldViolation> details, String requestId) {
        return new ErrorResponse(
                OffsetDateTime.now(clock.withZone(properties.timezone())).truncatedTo(ChronoUnit.SECONDS),
                code.status().value(),
                code.name(),
                message,
                path,
                details,
                requestId);
    }
}
