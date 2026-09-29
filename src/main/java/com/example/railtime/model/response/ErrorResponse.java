package com.example.railtime.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** Uniform error body for every API failure. Never contains stack traces. */
@Schema(description = "Standard error response")
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        @Schema(example = "2026-09-28T21:30:00+05:30") OffsetDateTime timestamp,
        @Schema(example = "400") int status,
        @Schema(example = "VALIDATION_ERROR") String error,
        @Schema(example = "Origin departure date is required") String message,
        @Schema(example = "/api/v1/booking/general/calculate") String path,
        @Schema(description = "Per-field validation failures, when applicable") List<FieldViolation> details) {

    @Schema(description = "A single invalid field")
    public record FieldViolation(
            @Schema(example = "originDepartureDate") String field,
            @Schema(example = "Origin departure date is required") String message) {
    }
}
