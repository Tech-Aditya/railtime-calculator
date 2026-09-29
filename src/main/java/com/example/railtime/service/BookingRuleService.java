package com.example.railtime.service;

import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.TatkalCategory;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.response.BookingRulesResponse;
import com.example.railtime.model.response.DisplayValues;
import com.example.railtime.properties.BookingRulesProperties;
import com.example.railtime.util.DateTimeUtil;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for the IRCTC booking rules the calculators apply.
 *
 * <h2>General reservation — Advance Reservation Period (ARP)</h2>
 * IRCTC states the ARP as "N days, excluding the date of journey". This is interpreted as:
 * the opening date {@code D} is the day for which the days {@code D, D+1, …, J−1} (the journey
 * date {@code J} not counted) number exactly {@code N}. Therefore {@code D = J − N days}.
 * <pre>
 *   J = 20 Dec 2026, N = 60  →  D = 21 Oct 2026
 *   (21 Oct … 19 Dec inclusive = 11 + 30 + 19 = 60 days; 20 Dec excluded)
 * </pre>
 * The subtraction uses {@link LocalDate#minusDays(long)}, i.e. real calendar arithmetic, so month
 * lengths, February, leap days and year boundaries are handled without special cases. Online
 * booking on the opening day starts at the configured opening time (08:00 IST).
 *
 * <h2>Tatkal</h2>
 * Tatkal opens {@code daysBeforeDeparture} (1) day before the origin departure date, excluding the
 * journey date: 10:00 IST for AC classes and 11:00 IST for non-AC classes. Classes not listed
 * among the standard Tatkal classes (1A) get no calculated time — see {@link TatkalCategory#NOT_LISTED}.
 *
 * <h2>Origin station</h2>
 * All dates are computed from the train's <em>originating-station</em> departure date, never from
 * a passenger's boarding date at an intermediate station.
 */
@Service
public class BookingRuleService {

    static final String NOTE_ORIGIN_BASIS =
            "Calculated from the train's originating-station departure date, not the boarding date.";
    static final String NOTE_SHORTER_ARP =
            "Some trains, including certain intercity/day trains, may have a shorter ARP. "
                    + "Verify the ARP for your specific train on IRCTC.";
    static final String NOTE_GENERAL_CONDITIONS =
            "Opening-day booking may be subject to additional IRCTC conditions, such as account "
                    + "or Aadhaar verification. Check IRCTC before booking.";
    static final String NOTE_TATKAL_QUOTA =
            "Tatkal quota is not available on every train or in every class. Check your train on IRCTC.";
    static final String NOTE_TATKAL_CONDITIONS =
            "Additional Tatkal conditions, such as account or Aadhaar verification, may apply. "
                    + "Check the latest IRCTC rules.";
    static final String NOTE_WINDOW_OPEN =
            "The booking window has opened. Check IRCTC for availability — this calculator does not "
                    + "show seat availability.";
    static final String NOTE_JOURNEY_PASSED = "This origin departure date is in the past.";
    static final String NOT_LISTED_TATKAL_MESSAGE =
            "%s Tatkal availability is not listed in the standard IRCTC Tatkal classes. "
                    + "Please verify availability for the specific train.";
    static final String DISCLAIMER =
            "This is an independent informational calculator and is not affiliated with or operated by "
                    + "IRCTC or Indian Railways. Booking rules can change. Always verify the current rules on "
                    + "the official IRCTC website before making travel plans.";

    private static final String NOTE_BOARDING_DIFFERS =
            "You board on %s, but the train leaves its origin on %s. The opening date above is based on "
                    + "the origin date.";
    private static final String DATE_TOO_OLD = "%s cannot be more than %d days in the past";
    private static final String DATE_TOO_FAR = "%s cannot be more than %d days in the future";

    private final BookingRulesProperties properties;
    private final Clock clock;
    private final List<String> generalNotes;
    private final Map<TatkalCategory, List<String>> tatkalNotes;
    private final BookingRulesResponse rulesDescription;

    public BookingRuleService(BookingRulesProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.generalNotes = buildGeneralNotes();
        this.tatkalNotes = buildTatkalNotes();
        this.rulesDescription = buildRulesDescription();
    }

    // ---------------------------------------------------------------- clock

    /** Current instant in the configured booking zone (IST), to millisecond precision. */
    public ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(zone())).truncatedTo(ChronoUnit.MILLIS);
    }

    public ZoneId zone() {
        return properties.timezone();
    }

    // ---------------------------------------------------------------- general reservation

    public int advanceReservationDays() {
        return properties.general().advanceDays();
    }

    /**
     * Opening date for general reservation: {@code journeyDate − ARP days}. See the class
     * documentation for why this equals "ARP days, excluding the date of journey".
     */
    public LocalDate generalOpeningDate(LocalDate journeyDate) {
        return journeyDate.minusDays(advanceReservationDays());
    }

    /** Opening date combined with the opening-day start time, in IST. */
    public ZonedDateTime generalOpeningDateTime(LocalDate journeyDate) {
        return DateTimeUtil.atZone(generalOpeningDate(journeyDate), properties.general().openingTime(), zone());
    }

    // ---------------------------------------------------------------- tatkal

    /** Tatkal opening date: {@code originDepartureDate − daysBeforeDeparture}. */
    public LocalDate tatkalOpeningDate(LocalDate originDepartureDate) {
        return originDepartureDate.minusDays(properties.tatkal().daysBeforeDeparture());
    }

    /**
     * Tatkal opening time for a class, or empty when the class is not listed among the standard
     * IRCTC Tatkal classes (no time is invented for such classes).
     */
    public Optional<LocalTime> tatkalOpeningTime(TravelClass travelClass) {
        return switch (travelClass.tatkalCategory()) {
            case AC -> Optional.of(properties.tatkal().acOpeningTime());
            case NON_AC -> Optional.of(properties.tatkal().nonAcOpeningTime());
            case NOT_LISTED -> Optional.empty();
        };
    }

    public String notListedTatkalMessage(TravelClass travelClass) {
        return NOT_LISTED_TATKAL_MESSAGE.formatted(travelClass.code());
    }

    // ---------------------------------------------------------------- shared rules

    /**
     * Status of a booking window at {@code now}. A journey date before today (IST) is reported as
     * passed; otherwise the window is open from the opening instant onwards (inclusive).
     */
    public BookingStatus statusOf(ZonedDateTime openingDateTime, LocalDate journeyDate, ZonedDateTime now) {
        if (journeyDate.isBefore(now.toLocalDate())) {
            return BookingStatus.JOURNEY_DATE_PASSED;
        }
        return now.isBefore(openingDateTime) ? BookingStatus.NOT_OPEN : BookingStatus.OPEN;
    }

    /** Countdown value: only meaningful while the window has not opened. */
    public Long millisUntilOpening(BookingStatus status, ZonedDateTime now, ZonedDateTime openingDateTime) {
        return status == BookingStatus.NOT_OPEN ? DateTimeUtil.millisUntil(now, openingDateTime) : null;
    }

    /**
     * Rejects dates outside the configured window (relative to today in IST). Past dates inside
     * the window are accepted and reported with {@link BookingStatus#JOURNEY_DATE_PASSED}.
     *
     * @param fieldLabel human label used in the error message, e.g. "Origin departure date"
     */
    public void validateDateWindow(LocalDate date, LocalDate today, String fieldLabel) {
        BookingRulesProperties.Validation window = properties.validation();
        if (date.isBefore(today.minusDays(window.maxDaysInPast()))) {
            throw new InvalidDateException(DATE_TOO_OLD.formatted(fieldLabel, window.maxDaysInPast()));
        }
        if (date.isAfter(today.plusDays(window.maxDaysInFuture()))) {
            throw new InvalidDateException(DATE_TOO_FAR.formatted(fieldLabel, window.maxDaysInFuture()));
        }
    }

    public DisplayValues display(ZonedDateTime openingDateTime, LocalDate journeyDate) {
        LocalDate bookingDate = openingDateTime.toLocalDate();
        return new DisplayValues(
                DateTimeUtil.formatLongDate(bookingDate),
                DateTimeUtil.formatWeekday(bookingDate),
                DateTimeUtil.formatTimeWithZone(openingDateTime),
                DateTimeUtil.formatLongDate(journeyDate));
    }

    // ---------------------------------------------------------------- notes

    /** Notes for a general reservation result; the static part is pre-built once. */
    public List<String> generalNotes(BookingStatus status, LocalDate journeyDate, LocalDate boardingDate) {
        List<String> notes = new ArrayList<>(generalNotes.size() + 2);
        notes.addAll(generalNotes);
        if (boardingDate != null && !boardingDate.equals(journeyDate)) {
            notes.add(NOTE_BOARDING_DIFFERS.formatted(
                    DateTimeUtil.formatLongDate(boardingDate), DateTimeUtil.formatLongDate(journeyDate)));
        }
        addStatusNote(notes, status);
        return List.copyOf(notes);
    }

    /** Notes for a Tatkal result. */
    public List<String> tatkalNotes(TatkalCategory category, BookingStatus status) {
        List<String> base = tatkalNotes.get(category);
        if (status != BookingStatus.OPEN && status != BookingStatus.JOURNEY_DATE_PASSED) {
            return base;
        }
        List<String> notes = new ArrayList<>(base);
        addStatusNote(notes, status);
        return List.copyOf(notes);
    }

    /** Immutable description of the configured rules (for the rules endpoint and the web page). */
    public BookingRulesResponse describeRules() {
        return rulesDescription;
    }

    private static void addStatusNote(List<String> notes, BookingStatus status) {
        if (status == BookingStatus.OPEN) {
            notes.add(NOTE_WINDOW_OPEN);
        } else if (status == BookingStatus.JOURNEY_DATE_PASSED) {
            notes.add(NOTE_JOURNEY_PASSED);
        }
    }

    private List<String> buildGeneralNotes() {
        return List.of(
                "Advance Reservation Period (ARP): %d days, excluding the date of journey."
                        .formatted(advanceReservationDays()),
                "On the opening day, online booking starts at %s IST."
                        .formatted(DateTimeUtil.formatTime(properties.general().openingTime())),
                NOTE_ORIGIN_BASIS,
                NOTE_SHORTER_ARP,
                NOTE_GENERAL_CONDITIONS);
    }

    private Map<TatkalCategory, List<String>> buildTatkalNotes() {
        int days = properties.tatkal().daysBeforeDeparture();
        String timing = "Tatkal opens %d %s before the origin departure date (journey date excluded)."
                .formatted(days, days == 1 ? "day" : "days");
        Map<TatkalCategory, List<String>> notes = new EnumMap<>(TatkalCategory.class);
        notes.put(TatkalCategory.AC, List.of(timing,
                "AC classes (%s) open at %s IST.".formatted(codesOf(TatkalCategory.AC),
                        DateTimeUtil.formatTime(properties.tatkal().acOpeningTime())),
                NOTE_ORIGIN_BASIS, NOTE_TATKAL_QUOTA, NOTE_TATKAL_CONDITIONS));
        notes.put(TatkalCategory.NON_AC, List.of(timing,
                "Non-AC classes (%s) open at %s IST.".formatted(codesOf(TatkalCategory.NON_AC),
                        DateTimeUtil.formatTime(properties.tatkal().nonAcOpeningTime())),
                NOTE_ORIGIN_BASIS, NOTE_TATKAL_QUOTA, NOTE_TATKAL_CONDITIONS));
        notes.put(TatkalCategory.NOT_LISTED, List.of(
                "Standard Tatkal classes: AC — %s; non-AC — %s."
                        .formatted(codesOf(TatkalCategory.AC), codesOf(TatkalCategory.NON_AC)),
                NOTE_TATKAL_QUOTA));
        return Map.copyOf(notes);
    }

    private BookingRulesResponse buildRulesDescription() {
        List<BookingRulesResponse.TravelClassRule> classes = Arrays.stream(TravelClass.values())
                .map(tc -> new BookingRulesResponse.TravelClassRule(
                        tc.code(), tc.displayName(), tc.tatkalCategory(), tatkalOpeningTime(tc).orElse(null)))
                .toList();
        return new BookingRulesResponse(
                zone().getId(),
                new BookingRulesResponse.GeneralRule(advanceReservationDays(), properties.general().openingTime(),
                        "Opens %d days before the train's origin departure date, excluding the date of journey."
                                .formatted(advanceReservationDays())),
                new BookingRulesResponse.TatkalRule(properties.tatkal().daysBeforeDeparture(),
                        properties.tatkal().acOpeningTime(), properties.tatkal().nonAcOpeningTime(), classes),
                List.of(DISCLAIMER, NOTE_SHORTER_ARP));
    }

    private static String codesOf(TatkalCategory category) {
        return Arrays.stream(TravelClass.values())
                .filter(tc -> tc.tatkalCategory() == category)
                .map(TravelClass::code)
                .collect(Collectors.joining(", "));
    }
}
