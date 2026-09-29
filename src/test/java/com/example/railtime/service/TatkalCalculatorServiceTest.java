package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.example.railtime.model.TatkalCategory;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.TatkalCalculationRequest;
import com.example.railtime.model.response.TatkalCalculationResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TatkalCalculatorServiceTest {

    private static final LocalDate ORIGIN = LocalDate.of(2026, 12, 20);

    private static TatkalCalculatorService serviceAt(Clock clock) {
        return new TatkalCalculatorService(new BookingRuleService(TestFixtures.defaultRules(), clock));
    }

    private final TatkalCalculatorService service = serviceAt(TestFixtures.fixedClock());

    @ParameterizedTest(name = "{0} → {1} ({2})")
    @CsvSource({
            "SL, 11:00, NON_AC",
            "FC, 11:00, NON_AC",
            "2S, 11:00, NON_AC",
            "2A, 10:00, AC",
            "3A, 10:00, AC",
            "CC, 10:00, AC",
            "EC, 10:00, AC",
            "3E, 10:00, AC"
    })
    void opensDayBeforeOriginAtClassTime(String code, LocalTime expectedTime, TatkalCategory expectedCategory) {
        TravelClass travelClass = TravelClass.fromCode(code);

        TatkalCalculationResponse response = service.calculate(new TatkalCalculationRequest(ORIGIN, travelClass));

        assertThat(response.type()).isEqualTo(BookingType.TATKAL);
        assertThat(response.travelClass()).isEqualTo(travelClass);
        assertThat(response.tatkalCategory()).isEqualTo(expectedCategory);
        assertThat(response.bookingDate()).isEqualTo(LocalDate.of(2026, 12, 19));
        assertThat(response.bookingTime()).isEqualTo(expectedTime);
        assertThat(response.bookingDateTime().toLocalDateTime()).isEqualTo(LocalDate.of(2026, 12, 19).atTime(expectedTime));
        assertThat(response.bookingDateTime().getOffset().getTotalSeconds()).isEqualTo(5 * 3600 + 30 * 60);
        assertThat(response.status()).isEqualTo(BookingStatus.NOT_OPEN);
        assertThat(response.message()).isNull();
        assertThat(response.display().bookingWeekday()).isEqualTo("Saturday");
    }

    @Test
    void sleeperSpecificationExample() {
        TatkalCalculationResponse response = service.calculate(new TatkalCalculationRequest(ORIGIN, TravelClass.SLEEPER));

        assertThat(response.bookingDateTime()).hasToString("2026-12-19T11:00+05:30");
        assertThat(response.display().bookingDate()).isEqualTo("19 December 2026");
        assertThat(response.display().bookingTime()).isEqualTo("11:00 AM IST");
        // 28 Sep 08:00 → 19 Dec 11:00 IST = 82 days 3 hours
        assertThat(response.millisUntilOpening()).isEqualTo((82L * 24 + 3) * 3_600_000);
    }

    @Test
    void firstAcIsNotApplicableWithoutAnyTime() {
        TatkalCalculationResponse response = service.calculate(new TatkalCalculationRequest(ORIGIN, TravelClass.FIRST_AC));

        assertThat(response.status()).isEqualTo(BookingStatus.NOT_APPLICABLE);
        assertThat(response.tatkalCategory()).isEqualTo(TatkalCategory.NOT_LISTED);
        assertThat(response.bookingDate()).isNull();
        assertThat(response.bookingTime()).isNull();
        assertThat(response.bookingDateTime()).isNull();
        assertThat(response.millisUntilOpening()).isNull();
        assertThat(response.display()).isNull();
        assertThat(response.message()).isEqualTo(
                "1A Tatkal availability is not listed in the standard IRCTC Tatkal classes. "
                        + "Please verify availability for the specific train.");
    }

    @Test
    void acOpensAnHourBeforeNonAc() {
        TatkalCalculatorService atTenAm = serviceAt(TestFixtures.clockAtIst(LocalDateTime.of(2026, 12, 19, 10, 0)));

        assertThat(atTenAm.calculate(new TatkalCalculationRequest(ORIGIN, TravelClass.THIRD_AC)).status())
                .isEqualTo(BookingStatus.OPEN);
        TatkalCalculationResponse sleeper = atTenAm.calculate(new TatkalCalculationRequest(ORIGIN, TravelClass.SLEEPER));
        assertThat(sleeper.status()).isEqualTo(BookingStatus.NOT_OPEN);
        assertThat(sleeper.millisUntilOpening()).isEqualTo(3_600_000L);
    }

    @Test
    void crossesYearBoundary() {
        TatkalCalculationResponse response = service.calculate(
                new TatkalCalculationRequest(LocalDate.of(2027, 1, 1), TravelClass.SECOND_AC));

        assertThat(response.bookingDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void pastOriginDateIsReported() {
        TatkalCalculationResponse response = service.calculate(
                new TatkalCalculationRequest(LocalDate.of(2026, 9, 1), TravelClass.SLEEPER));

        assertThat(response.status()).isEqualTo(BookingStatus.JOURNEY_DATE_PASSED);
        assertThat(response.notes()).contains(BookingRuleService.NOTE_JOURNEY_PASSED);
    }

    @Test
    void rejectsOriginBeyondAcceptedWindow() {
        assertThatThrownBy(() -> service.calculate(
                new TatkalCalculationRequest(LocalDate.of(2024, 1, 1), TravelClass.FIRST_AC)))
                .isInstanceOf(InvalidDateException.class);
    }
}
