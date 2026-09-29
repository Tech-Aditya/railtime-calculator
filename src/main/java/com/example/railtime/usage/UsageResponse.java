package com.example.railtime.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Anonymous usage counts since {@code since} (server start). Weeks start on Monday; days, weeks and
 * months are in IST.
 */
@Schema(description = "Anonymous usage counts since the server started")
public record UsageResponse(
        @Schema(description = "When counting started (server start)", example = "2026-09-29T08:00:00+05:30") OffsetDateTime since,
        @Schema(example = "2026-09-29T22:15:00+05:30") OffsetDateTime asOf,
        Visits visits,
        Calculations calculations,
        @Schema(description = "One point per day, oldest first: the last 30 days, or fewer if counting started more recently")
        List<DailyPoint> daily) {

    @Schema(description = "Page visits (full page loads, bots excluded)")
    public record Visits(long total, long today, long thisWeek, long thisMonth) {
    }

    @Schema(description = "Successful calculations")
    public record Calculations(long total, long today, long general, long tatkal, long refund, long chart) {
    }

    public record DailyPoint(@Schema(example = "2026-09-29") LocalDate date, long visits, long calculations) {
    }
}
