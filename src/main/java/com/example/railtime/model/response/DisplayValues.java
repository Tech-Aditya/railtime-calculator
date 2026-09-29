package com.example.railtime.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Human-readable, server-formatted values, so every client renders IST dates identically
 * regardless of the browser's own time zone.
 */
@Schema(description = "Pre-formatted display strings (English, IST)")
public record DisplayValues(
        @Schema(example = "21 October 2026") String bookingDate,
        @Schema(example = "Wednesday") String bookingWeekday,
        @Schema(example = "08:00 AM IST") String bookingTime,
        @Schema(example = "20 December 2026") String journeyDate) {
}
