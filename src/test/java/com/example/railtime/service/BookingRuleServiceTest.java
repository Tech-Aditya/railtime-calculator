package com.example.railtime.service;

import static com.example.railtime.TestFixtures.IST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.TatkalCategory;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.response.BookingRulesResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class BookingRuleServiceTest {

    private final BookingRuleService rules = new BookingRuleService(TestFixtures.defaultRules(), TestFixtures.fixedClock());

    @Nested
    @DisplayName("General reservation — 60-day ARP, journey date excluded")
    class GeneralOpeningDate {

        @ParameterizedTest(name = "journey {0} → opens {1} ({2})")
        @CsvSource({
                "2026-12-20, 2026-10-21, specification example",
                "2026-11-30, 2026-10-01, month boundary",
                "2026-04-30, 2026-03-01, crosses non-leap February",
                "2026-01-01, 2025-11-02, year boundary (1 Jan)",
                "2026-02-28, 2025-12-30, end of February, crosses year",
                "2026-03-01, 2025-12-31, 1 March in a non-leap year",
                "2028-02-29, 2027-12-31, leap day journey",
                "2028-03-01, 2028-01-01, 1 March in a leap year",
                "2028-04-29, 2028-02-29, opening falls on a leap day",
                "2026-12-31, 2026-11-01, 31 December",
                "2027-01-01, 2026-11-02, December to January",
                "2030-06-15, 2030-04-16, far future"
        })
        void opensSixtyDaysBeforeJourney(LocalDate journey, LocalDate expectedOpening, String scenario) {
            assertThat(rules.generalOpeningDate(journey)).as(scenario).isEqualTo(expectedOpening);
        }

        @Test
        @DisplayName("for every day 2025–2029: opening day counts as day 1, exactly 60 days precede the journey date")
        void bookingWindowExcludesJourneyDate() {
            LocalDate.of(2025, 1, 1).datesUntil(LocalDate.of(2030, 1, 1)).forEach(journey -> {
                LocalDate opening = rules.generalOpeningDate(journey);

                // Days opening, opening+1, …, journey−1 (journey date itself excluded) must number exactly 60.
                assertThat(opening.datesUntil(journey).count()).as("journey %s", journey).isEqualTo(60);
                assertThat(ChronoUnit.DAYS.between(opening, journey)).as("journey %s", journey).isEqualTo(60);
            });
        }

        @Test
        void opensAtEightAmIst() {
            ZonedDateTime opening = rules.generalOpeningDateTime(LocalDate.of(2026, 12, 20));

            assertThat(opening.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 21, 8, 0));
            assertThat(opening.getZone()).isEqualTo(IST);
            assertThat(opening.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
            assertThat(opening.toOffsetDateTime()).hasToString("2026-10-21T08:00+05:30");
        }

        @Test
        void arpIsConfigurable() {
            BookingRuleService oneTwenty = new BookingRuleService(TestFixtures.rulesWithArp(120), TestFixtures.fixedClock());

            assertThat(oneTwenty.generalOpeningDate(LocalDate.of(2026, 12, 20))).isEqualTo(LocalDate.of(2026, 8, 22));
            assertThat(oneTwenty.advanceReservationDays()).isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("Tatkal")
    class Tatkal {

        @ParameterizedTest(name = "origin {0} → opens {1}")
        @CsvSource({
                "2026-12-20, 2026-12-19",
                "2026-11-01, 2026-10-31",
                "2027-01-01, 2026-12-31",
                "2028-03-01, 2028-02-29",
                "2026-03-01, 2026-02-28"
        })
        void opensOneDayBeforeOrigin(LocalDate origin, LocalDate expected) {
            assertThat(rules.tatkalOpeningDate(origin)).isEqualTo(expected);
        }

        @ParameterizedTest
        @EnumSource(value = TravelClass.class, names = {"SECOND_AC", "THIRD_AC", "AC_CHAIR_CAR", "EXECUTIVE_CHAIR_CAR", "AC_THREE_ECONOMY"})
        void acClassesOpenAtTen(TravelClass travelClass) {
            assertThat(rules.tatkalOpeningTime(travelClass)).contains(LocalTime.of(10, 0));
        }

        @ParameterizedTest
        @EnumSource(value = TravelClass.class, names = {"SLEEPER", "FIRST_CLASS", "SECOND_SITTING"})
        void nonAcClassesOpenAtEleven(TravelClass travelClass) {
            assertThat(rules.tatkalOpeningTime(travelClass)).contains(LocalTime.of(11, 0));
        }

        @Test
        void firstAcHasNoInventedTime() {
            assertThat(rules.tatkalOpeningTime(TravelClass.FIRST_AC)).isEmpty();
            assertThat(rules.notListedTatkalMessage(TravelClass.FIRST_AC)).isEqualTo(
                    "1A Tatkal availability is not listed in the standard IRCTC Tatkal classes. "
                            + "Please verify availability for the specific train.");
        }
    }

    @Nested
    @DisplayName("Status")
    class Status {

        private final LocalDate journey = LocalDate.of(2026, 12, 20);
        private final ZonedDateTime opening = ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, IST);

        @Test
        void notOpenBeforeOpeningInstant() {
            assertThat(rules.statusOf(opening, journey, opening.minusSeconds(1))).isEqualTo(BookingStatus.NOT_OPEN);
        }

        @Test
        void openExactlyAtOpeningInstant() {
            assertThat(rules.statusOf(opening, journey, opening)).isEqualTo(BookingStatus.OPEN);
        }

        @Test
        void openOnJourneyDay() {
            ZonedDateTime journeyMorning = journey.atTime(6, 0).atZone(IST);
            assertThat(rules.statusOf(opening, journey, journeyMorning)).isEqualTo(BookingStatus.OPEN);
        }

        @Test
        void passedAfterJourneyDay() {
            ZonedDateTime dayAfter = journey.plusDays(1).atStartOfDay(IST);
            assertThat(rules.statusOf(opening, journey, dayAfter)).isEqualTo(BookingStatus.JOURNEY_DATE_PASSED);
        }

        @Test
        void countdownOnlyWhileNotOpen() {
            ZonedDateTime now = opening.minusMinutes(1);
            assertThat(rules.millisUntilOpening(BookingStatus.NOT_OPEN, now, opening)).isEqualTo(60_000L);
            assertThat(rules.millisUntilOpening(BookingStatus.OPEN, now, opening)).isNull();
            assertThat(rules.millisUntilOpening(BookingStatus.JOURNEY_DATE_PASSED, now, opening)).isNull();
        }

        @Test
        void nowIsExpressedInIst() {
            assertThat(rules.now()).isEqualTo(ZonedDateTime.of(2026, 9, 28, 8, 0, 0, 0, IST));
        }
    }

    @Nested
    @DisplayName("Accepted date window")
    class DateWindow {

        private final LocalDate today = LocalDate.of(2026, 9, 28);

        @Test
        void acceptsBoundaries() {
            rules.validateDateWindow(today.minusDays(365), today, "Date");
            rules.validateDateWindow(today, today, "Date");
            rules.validateDateWindow(today.plusDays(730), today, "Date");
        }

        @Test
        void rejectsTooFarInPast() {
            assertThatThrownBy(() -> rules.validateDateWindow(today.minusDays(366), today, "Origin departure date"))
                    .isInstanceOf(InvalidDateException.class)
                    .hasMessage("Origin departure date cannot be more than 365 days in the past");
        }

        @Test
        void rejectsTooFarInFuture() {
            assertThatThrownBy(() -> rules.validateDateWindow(today.plusDays(731), today, "Origin departure date"))
                    .isInstanceOf(InvalidDateException.class)
                    .hasMessage("Origin departure date cannot be more than 730 days in the future");
        }
    }

    @Nested
    @DisplayName("Notes and rule description")
    class Notes {

        @Test
        void generalNotesIncludeArpAndShorterArpWarning() {
            List<String> notes = rules.generalNotes(BookingStatus.NOT_OPEN, LocalDate.of(2026, 12, 20), null);

            assertThat(notes).contains(
                    "Advance Reservation Period (ARP): 60 days, excluding the date of journey.",
                    "On the opening day, online booking starts at 08:00 AM IST.",
                    BookingRuleService.NOTE_SHORTER_ARP,
                    BookingRuleService.NOTE_ORIGIN_BASIS);
        }

        @Test
        void boardingNoteOnlyWhenBoardingDiffers() {
            LocalDate journey = LocalDate.of(2026, 12, 20);

            assertThat(rules.generalNotes(BookingStatus.NOT_OPEN, journey, journey))
                    .noneMatch(note -> note.startsWith("You board"));
            assertThat(rules.generalNotes(BookingStatus.NOT_OPEN, journey, journey.plusDays(1)))
                    .contains("You board on 21 December 2026, but the train leaves its origin on 20 December 2026. "
                            + "The opening date above is based on the origin date.");
        }

        @Test
        void statusSpecificNotes() {
            LocalDate journey = LocalDate.of(2026, 12, 20);
            assertThat(rules.generalNotes(BookingStatus.OPEN, journey, null)).contains(BookingRuleService.NOTE_WINDOW_OPEN);
            assertThat(rules.generalNotes(BookingStatus.JOURNEY_DATE_PASSED, journey, null))
                    .contains(BookingRuleService.NOTE_JOURNEY_PASSED);
            assertThat(rules.tatkalNotes(TatkalCategory.AC, BookingStatus.OPEN)).contains(BookingRuleService.NOTE_WINDOW_OPEN);
            assertThat(rules.tatkalNotes(TatkalCategory.NON_AC, BookingStatus.JOURNEY_DATE_PASSED))
                    .contains(BookingRuleService.NOTE_JOURNEY_PASSED);
        }

        @Test
        void tatkalNotesDescribeTheClassCategory() {
            assertThat(rules.tatkalNotes(TatkalCategory.AC, BookingStatus.NOT_OPEN))
                    .contains("AC classes (2A, 3A, CC, EC, 3E) open at 10:00 AM IST.");
            assertThat(rules.tatkalNotes(TatkalCategory.NON_AC, BookingStatus.NOT_OPEN))
                    .contains("Non-AC classes (SL, FC, 2S) open at 11:00 AM IST.");
            assertThat(rules.tatkalNotes(TatkalCategory.NOT_LISTED, BookingStatus.NOT_APPLICABLE))
                    .contains("Standard Tatkal classes: AC — 2A, 3A, CC, EC, 3E; non-AC — SL, FC, 2S.");
        }

        @Test
        void describesConfiguredRules() {
            BookingRulesResponse description = rules.describeRules();

            assertThat(description.timeZone()).isEqualTo("Asia/Kolkata");
            assertThat(description.general().arpDays()).isEqualTo(60);
            assertThat(description.general().openingTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(description.tatkal().classes()).hasSize(TravelClass.values().length);
            assertThat(description.tatkal().classes())
                    .filteredOn(c -> c.code().equals("1A"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.openingTime()).isNull());
            assertThat(description.disclaimers()).contains(BookingRuleService.DISCLAIMER);
        }
    }
}
