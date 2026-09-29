package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.example.railtime.model.request.ReservationCalculationRequest;
import com.example.railtime.model.response.ReservationCalculationResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ReservationCalculatorServiceTest {

    private static final LocalDate JOURNEY = LocalDate.of(2026, 12, 20);

    private static ReservationCalculatorService serviceAt(Clock clock) {
        return new ReservationCalculatorService(new BookingRuleService(TestFixtures.defaultRules(), clock));
    }

    private final ReservationCalculatorService service = serviceAt(TestFixtures.fixedClock());

    @Test
    void calculatesSpecificationExample() {
        ReservationCalculationResponse response = service.calculate(new ReservationCalculationRequest(JOURNEY, null));

        assertThat(response.type()).isEqualTo(BookingType.GENERAL_RESERVATION);
        assertThat(response.journeyDate()).isEqualTo(JOURNEY);
        assertThat(response.bookingDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(response.bookingTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(response.bookingDateTime()).hasToString("2026-10-21T08:00+05:30");
        assertThat(response.arpDays()).isEqualTo(60);
        assertThat(response.status()).isEqualTo(BookingStatus.NOT_OPEN);
        // 28 Sep 08:00 IST → 21 Oct 08:00 IST = exactly 23 days
        assertThat(response.millisUntilOpening()).isEqualTo(23L * 24 * 60 * 60 * 1000);
        assertThat(response.calculatedAt()).hasToString("2026-09-28T08:00+05:30");
        assertThat(response.display().bookingDate()).isEqualTo("21 October 2026");
        assertThat(response.display().bookingWeekday()).isEqualTo("Wednesday");
        assertThat(response.display().bookingTime()).isEqualTo("08:00 AM IST");
        assertThat(response.display().journeyDate()).isEqualTo("20 December 2026");
        assertThat(response.notes()).isNotEmpty();
    }

    @Test
    void notOpenOneSecondBeforeEightAm() {
        ReservationCalculationResponse response = serviceAt(TestFixtures.clockAtIst(LocalDateTime.of(2026, 10, 21, 7, 59, 59)))
                .calculate(new ReservationCalculationRequest(JOURNEY, null));

        assertThat(response.status()).isEqualTo(BookingStatus.NOT_OPEN);
        assertThat(response.millisUntilOpening()).isEqualTo(1_000L);
    }

    @Test
    void openAtExactlyEightAm() {
        ReservationCalculationResponse response = serviceAt(TestFixtures.clockAtIst(LocalDateTime.of(2026, 10, 21, 8, 0)))
                .calculate(new ReservationCalculationRequest(JOURNEY, null));

        assertThat(response.status()).isEqualTo(BookingStatus.OPEN);
        assertThat(response.millisUntilOpening()).isNull();
        assertThat(response.notes()).contains(BookingRuleService.NOTE_WINDOW_OPEN);
    }

    @Test
    void journeyTodayIsOpen() {
        ReservationCalculationResponse response = service.calculate(
                new ReservationCalculationRequest(LocalDate.of(2026, 9, 28), null));

        assertThat(response.bookingDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(response.status()).isEqualTo(BookingStatus.OPEN);
    }

    @Test
    void pastJourneyDateIsReportedNotRejected() {
        ReservationCalculationResponse response = service.calculate(
                new ReservationCalculationRequest(LocalDate.of(2026, 9, 27), null));

        assertThat(response.status()).isEqualTo(BookingStatus.JOURNEY_DATE_PASSED);
        assertThat(response.millisUntilOpening()).isNull();
        assertThat(response.notes()).contains(BookingRuleService.NOTE_JOURNEY_PASSED);
    }

    @Test
    void rejectsJourneyBeyondAcceptedWindow() {
        assertThatThrownBy(() -> service.calculate(new ReservationCalculationRequest(LocalDate.of(2030, 1, 1), null)))
                .isInstanceOf(InvalidDateException.class)
                .hasMessageContaining("730 days in the future");
    }

    @Test
    void boardingDateNeverChangesTheOpeningDate() {
        ReservationCalculationResponse response = service.calculate(
                new ReservationCalculationRequest(JOURNEY, JOURNEY.plusDays(1)));

        assertThat(response.bookingDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(response.boardingDate()).isEqualTo(JOURNEY.plusDays(1));
        assertThat(response.notes()).anyMatch(note -> note.startsWith("You board on 21 December 2026"));
    }

    @Test
    void rejectsBoardingBeforeOrigin() {
        assertThatThrownBy(() -> service.calculate(new ReservationCalculationRequest(JOURNEY, JOURNEY.minusDays(1))))
                .isInstanceOf(InvalidDateException.class)
                .hasMessage(ReservationCalculatorService.BOARDING_BEFORE_ORIGIN);
    }

    @Test
    void rejectsBoardingBeyondAcceptedWindow() {
        LocalDate lastAcceptedOrigin = LocalDate.of(2026, 9, 28).plusDays(730);
        assertThatThrownBy(() -> service.calculate(
                new ReservationCalculationRequest(lastAcceptedOrigin, lastAcceptedOrigin.plusDays(1))))
                .isInstanceOf(InvalidDateException.class)
                .hasMessageStartingWith("Boarding date");
    }
}
