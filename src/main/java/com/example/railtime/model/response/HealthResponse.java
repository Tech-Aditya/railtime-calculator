package com.example.railtime.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Lightweight public liveness payload. */
@Schema(description = "Application liveness")
public record HealthResponse(
        @Schema(example = "UP") String status,
        @Schema(example = "RailTime Calculator") String application,
        @Schema(example = "1.0.0") String version,
        @Schema(example = "2026-09-28T21:30:00+05:30") OffsetDateTime serverTime) {
}
