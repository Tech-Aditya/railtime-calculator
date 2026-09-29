package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.TicketStatus;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.CancellationRequest;
import com.example.railtime.model.response.CancellationResponse;
import com.example.railtime.model.response.CancellationResponse.CancellationSlab;
import com.example.railtime.properties.BookingRulesProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Clock pinned to 28 Sep 2026, 08:00 IST; departure 20 Dec 2026, 18:30 IST unless stated. */
class CancellationRefundServiceTest {

    private static final LocalDate DEPARTURE_DATE = LocalDate.of(2026, 12, 20);
    private static final LocalTime DEPARTURE_TIME = LocalTime.of(18, 30);

    private final CancellationRefundService service = new CancellationRefundService(
            new BookingRuleService(TestFixtures.defaultRules(), TestFixtures.fixedClock()), TestFixtures.defaultRules());

    private CancellationResponse cancel(String code, TicketStatus status, String fare, int passengers,
                                        String cancelDate, String cancelTime) {
        return service.calculate(new CancellationRequest(TravelClass.fromCode(code), status, new BigDecimal(fare),
                passengers, DEPARTURE_DATE, DEPARTURE_TIME,
                cancelDate == null ? null : LocalDate.parse(cancelDate),
                cancelTime == null ? null : LocalTime.parse(cancelTime)));
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Test
    void flatChargeMoreThan48HoursBeforeWithGstOnAcClass() {
        CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-18", "18:29");

        assertThat(r.slab()).isEqualTo(CancellationSlab.FLAT_CHARGE);
        assertThat(r.refundable()).isTrue();
        assertThat(r.minutesBeforeDeparture()).isEqualTo(48 * 60 + 1);
        assertMoney(r.farePerPassenger(), "1225.00");
        assertMoney(r.chargePerPassenger(), "180.00");
        assertMoney(r.gstPerPassenger(), "9.00");
        assertMoney(r.totalCharge(), "360.00");
        assertMoney(r.totalGst(), "18.00");
        assertMoney(r.totalRefund(), "2072.00");
        assertThat(r.slabDescription()).isEqualTo("More than 48 hours before departure: flat ₹180 per passenger");
        assertThat(r.travelClassName()).isEqualTo("Third AC");
    }

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource({
            "2026-12-18, 18:29, FLAT_CHARGE",
            "2026-12-18, 18:30, QUARTER_OF_FARE",
            "2026-12-20, 06:30, QUARTER_OF_FARE",
            "2026-12-20, 06:31, HALF_OF_FARE",
            "2026-12-20, 14:30, HALF_OF_FARE",
            "2026-12-20, 14:31, NO_REFUND_CONFIRMED",
            "2026-12-20, 18:29, NO_REFUND_CONFIRMED"
    })
    void confirmedSlabBoundaries(String date, String time, CancellationSlab expected) {
        assertThat(cancel("SL", TicketStatus.CONFIRMED, "500", 1, date, time).slab()).isEqualTo(expected);
    }

    @Test
    void quarterOfFareWithGstRoundedHalfUp() {
        CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-19", "18:30");

        assertThat(r.slab()).isEqualTo(CancellationSlab.QUARTER_OF_FARE);
        assertMoney(r.chargePerPassenger(), "306.25");
        assertMoney(r.gstPerPassenger(), "15.31");
        assertMoney(r.totalCharge(), "612.50");
        assertMoney(r.totalGst(), "30.62");
        assertMoney(r.totalRefund(), "1806.88");
        assertThat(r.slabDescription()).contains("48 to 12 hours", "25% of fare", "minimum ₹180 per passenger");
    }

    @Test
    void halfOfFare() {
        CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-20", "10:00");

        assertThat(r.slab()).isEqualTo(CancellationSlab.HALF_OF_FARE);
        assertMoney(r.chargePerPassenger(), "612.50");
        assertMoney(r.gstPerPassenger(), "30.63");
        assertMoney(r.totalRefund(), "1163.74");
        assertThat(r.slabDescription()).contains("12 to 4 hours", "50% of fare");
    }

    @Test
    void percentageChargeNeverBelowTheFlatCharge() {
        CancellationResponse r = cancel("SL", TicketStatus.CONFIRMED, "300", 1, "2026-12-19", "18:30");

        assertThat(r.slab()).isEqualTo(CancellationSlab.QUARTER_OF_FARE);
        assertMoney(r.chargePerPassenger(), "120.00");
        assertMoney(r.gstPerPassenger(), "0.00");
        assertMoney(r.totalRefund(), "180.00");
    }

    @Test
    void chargeIsCappedAtTheFare() {
        CancellationResponse r = cancel("2S", TicketStatus.CONFIRMED, "40", 1, "2026-12-01", "10:00");

        assertMoney(r.chargePerPassenger(), "40.00");
        assertMoney(r.totalRefund(), "0.00");
    }

    @Test
    void gstIsCappedAtWhatIsLeftOfTheFare() {
        CancellationResponse r = cancel("1A", TicketStatus.CONFIRMED, "245", 1, "2026-12-01", "10:00");

        assertMoney(r.chargePerPassenger(), "240.00");
        assertMoney(r.gstPerPassenger(), "5.00");
        assertMoney(r.totalRefund(), "0.00");
    }

    @Test
    void noRefundWithinFourHoursKeepsTheWholeFare() {
        CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-20", "16:00");

        assertThat(r.refundable()).isFalse();
        assertMoney(r.totalCharge(), "2450.00");
        assertMoney(r.totalGst(), "0.00");
        assertMoney(r.totalRefund(), "0.00");
        assertThat(r.slabDescription()).isEqualTo("Within 4 hours of departure: no refund on confirmed tickets");
    }

    @Test
    void confirmedTatkalIsNeverRefunded() {
        CancellationResponse r = cancel("SL", TicketStatus.CONFIRMED_TATKAL, "1240.50", 3, "2026-12-01", "10:00");

        assertThat(r.slab()).isEqualTo(CancellationSlab.NO_REFUND_TATKAL);
        assertMoney(r.totalRefund(), "0.00");
        assertThat(r.slabDescription()).isEqualTo("Confirmed Tatkal tickets: no refund on cancellation");
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({
            "RAC, 18:00, CLERKAGE",
            "WAITLISTED, 18:00, CLERKAGE",
            "RAC, 18:01, NO_REFUND_RAC_WAITLIST",
            "WAITLISTED, 18:01, NO_REFUND_RAC_WAITLIST"
    })
    void racAndWaitlistPayClerkageUntil30MinutesBefore(TicketStatus status, String time, CancellationSlab expected) {
        CancellationResponse r = cancel("3A", status, "1000", 2, "2026-12-20", time);

        assertThat(r.slab()).isEqualTo(expected);
        if (expected == CancellationSlab.CLERKAGE) {
            assertMoney(r.chargePerPassenger(), "60.00");
            assertMoney(r.gstPerPassenger(), "3.00");
            assertMoney(r.totalRefund(), "874.00");
            assertThat(r.slabDescription()).contains("clerkage ₹60 per passenger");
        } else {
            assertMoney(r.totalRefund(), "0.00");
            assertThat(r.slabDescription()).contains("Within 30 minutes");
        }
    }

    @Test
    void cancellationTimeDefaultsToNow() {
        CancellationResponse r = service.calculate(new CancellationRequest(TravelClass.SLEEPER, TicketStatus.CONFIRMED,
                new BigDecimal("500"), 1, LocalDate.of(2026, 9, 30), LocalTime.of(8, 0), null, null));

        assertThat(r.minutesBeforeDeparture()).isEqualTo(48 * 60);
        assertThat(r.slab()).isEqualTo(CancellationSlab.QUARTER_OF_FARE);
        assertThat(r.cancellationDateTime()).hasToString("2026-09-28T08:00+05:30");
    }

    @Test
    void rejectsHalfAFilledCancellationTime() {
        assertThatThrownBy(() -> cancel("SL", TicketStatus.CONFIRMED, "500", 1, "2026-12-01", null))
                .isInstanceOf(InvalidDateException.class)
                .hasMessage(CancellationRefundService.PARTIAL_CANCELLATION_TIME);
        assertThatThrownBy(() -> cancel("SL", TicketStatus.CONFIRMED, "500", 1, null, "10:00"))
                .isInstanceOf(InvalidDateException.class)
                .hasMessage(CancellationRefundService.PARTIAL_CANCELLATION_TIME);
    }

    @ParameterizedTest
    @CsvSource({"2026-12-20, 18:30", "2026-12-21, 09:00"})
    void rejectsCancellationAtOrAfterDeparture(String date, String time) {
        assertThatThrownBy(() -> cancel("SL", TicketStatus.CONFIRMED, "500", 1, date, time))
                .isInstanceOf(InvalidDateException.class)
                .hasMessage(CancellationRefundService.CANCEL_AFTER_DEPARTURE);
    }

    @Test
    void rejectsDepartureOutsideTheSupportedWindow() {
        assertThatThrownBy(() -> service.calculate(new CancellationRequest(TravelClass.SLEEPER, TicketStatus.CONFIRMED,
                new BigDecimal("500"), 1, LocalDate.of(2030, 1, 1), LocalTime.NOON, null, null)))
                .isInstanceOf(InvalidDateException.class);
    }

    @Test
    void notesMentionGstOnlyForGstClassesAndTdrOnlyForConfirmed() {
        assertThat(cancel("3A", TicketStatus.CONFIRMED, "1000", 1, "2026-12-01", "10:00").notes())
                .first().isEqualTo(CancellationRefundService.NOTE_SOURCE);
        assertThat(cancel("3A", TicketStatus.CONFIRMED, "1000", 1, "2026-12-01", "10:00").notes())
                .anyMatch(n -> n.startsWith("GST (5%)"))
                .anyMatch(n -> n.contains("TDR"))
                .contains(CancellationRefundService.NOTE_FEES)
                .last().isEqualTo(CancellationRefundService.NOTE_VERIFY);
        assertThat(cancel("SL", TicketStatus.RAC, "1000", 1, "2026-12-01", "10:00").notes())
                .noneMatch(n -> n.startsWith("GST"))
                .noneMatch(n -> n.contains("TDR"));
    }

    @Test
    void failsFastWhenAClassHasNoFlatCharge() {
        BookingRulesProperties defaults = TestFixtures.defaultRules();
        BookingRulesProperties.Cancellation c = defaults.cancellation();
        Map<String, BigDecimal> flat = new LinkedHashMap<>(c.flatCharges());
        flat.remove("2S");
        BookingRulesProperties broken = new BookingRulesProperties(defaults.timezone(), defaults.general(), defaults.tatkal(),
                defaults.validation(),
                new BookingRulesProperties.Cancellation(flat, c.clerkage(), c.gstRatePercent(), c.gstClasses(),
                        c.flatChargeAboveHours(), c.quarterChargeFromHours(), c.quarterRatePercent(), c.halfRatePercent(),
                        c.confirmedCutoffHours(), c.racWaitlistCutoffMinutes()),
                defaults.chart());

        assertThatThrownBy(() -> new CancellationRefundService(
                new BookingRuleService(broken, TestFixtures.fixedClock()), broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2S");
    }
}
