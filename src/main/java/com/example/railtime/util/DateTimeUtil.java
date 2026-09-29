package com.example.railtime.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Stateless {@code java.time} helpers. All formatters are immutable and thread-safe.
 *
 * <p>Display strings are always English so that the UI and API agree regardless of the
 * server or browser locale.
 */
public final class DateTimeUtil {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_WITH_ZONE = DateTimeFormatter.ofPattern("hh:mm a zzz", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private DateTimeUtil() {
    }

    /** Combines a local date and wall-clock time into an instant in the given zone. */
    public static ZonedDateTime atZone(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone);
    }

    /** {@code 21 October 2026} */
    public static String formatLongDate(LocalDate date) {
        return LONG_DATE.format(date);
    }

    /** {@code Wednesday} */
    public static String formatWeekday(LocalDate date) {
        return WEEKDAY.format(date);
    }

    /** {@code 08:00 AM IST} — the zone abbreviation comes from the date-time's own zone. */
    public static String formatTimeWithZone(ZonedDateTime dateTime) {
        return TIME_WITH_ZONE.format(dateTime);
    }

    /** {@code 08:00 AM} */
    public static String formatTime(LocalTime time) {
        return TIME.format(time);
    }

    /** Milliseconds from {@code now} until {@code target}; never negative. */
    public static long millisUntil(ZonedDateTime now, ZonedDateTime target) {
        Duration remaining = Duration.between(now, target);
        return remaining.isNegative() ? 0L : remaining.toMillis();
    }
}
