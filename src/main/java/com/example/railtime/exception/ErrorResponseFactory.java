package com.example.railtime.exception;

import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.properties.BookingRulesProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds {@link ErrorResponse} bodies with an IST timestamp; shared by MVC and security error paths. */
@Component
public class ErrorResponseFactory {

    private final Clock clock;
    private final BookingRulesProperties properties;

    public ErrorResponseFactory(Clock clock, BookingRulesProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public ErrorResponse create(ApiErrorCode code, String message, String path) {
        return create(code, message, path, List.of());
    }

    public ErrorResponse create(ApiErrorCode code, String message, String path,
                                List<ErrorResponse.FieldViolation> details) {
        return new ErrorResponse(
                OffsetDateTime.now(clock.withZone(properties.timezone())).truncatedTo(ChronoUnit.SECONDS),
                code.status().value(),
                code.name(),
                message,
                path,
                details);
    }
}
