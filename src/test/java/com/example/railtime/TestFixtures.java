package com.example.railtime;

import com.example.railtime.properties.BookingRulesProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared deterministic fixtures for tests. */
public final class TestFixtures {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** 28 September 2026, 08:00:00 IST. */
    public static final Instant NOW = LocalDateTime.of(2026, 9, 28, 8, 0).atZone(IST).toInstant();

    private TestFixtures() {
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** A fixed clock at the given IST wall-clock time. */
    public static Clock clockAtIst(LocalDateTime istDateTime) {
        return Clock.fixed(istDateTime.atZone(IST).toInstant(), ZoneOffset.UTC);
    }

    /** The production default rules: 60-day ARP at 08:00; Tatkal 1 day before at 10:00 / 11:00. */
    public static BookingRulesProperties defaultRules() {
        return rulesWithArp(60);
    }

    public static BookingRulesProperties rulesWithArp(int arpDays) {
        return new BookingRulesProperties(
                IST,
                new BookingRulesProperties.General(arpDays, LocalTime.of(8, 0)),
                new BookingRulesProperties.Tatkal(1, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                new BookingRulesProperties.Validation(365, 730),
                cancellationRules(),
                chartRules());
    }

    /** The published 2015 cancellation rules (flat charges, 25% / 50% slabs, ₹60 clerkage, 5% GST on AC / FC). */
    public static BookingRulesProperties.Cancellation cancellationRules() {
        Map<String, BigDecimal> flat = new LinkedHashMap<>();
        flat.put("1A", new BigDecimal("240"));
        flat.put("EC", new BigDecimal("240"));
        flat.put("2A", new BigDecimal("200"));
        flat.put("FC", new BigDecimal("200"));
        flat.put("3A", new BigDecimal("180"));
        flat.put("CC", new BigDecimal("180"));
        flat.put("3E", new BigDecimal("180"));
        flat.put("SL", new BigDecimal("120"));
        flat.put("2S", new BigDecimal("60"));
        return new BookingRulesProperties.Cancellation(flat, new BigDecimal("60"), new BigDecimal("5"),
                Set.of("1A", "EC", "2A", "FC", "3A", "CC", "3E"), 48, 12, new BigDecimal("25"), new BigDecimal("50"), 4, 30);
    }

    /** Charting per the Railway Board directive effective 10 July 2025. */
    public static BookingRulesProperties.Chart chartRules() {
        return new BookingRulesProperties.Chart(8, LocalTime.of(5, 0), LocalTime.of(14, 0), LocalTime.of(21, 0), 30,
                LocalDate.of(2025, 7, 10));
    }
}
