package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.exception.RuleViolationException;
import com.example.railtime.model.TicketStatus;
import com.example.railtime.model.TrainType;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.CancellationRequest;
import com.example.railtime.model.response.CancellationResponse;
import com.example.railtime.model.response.CancellationResponse.CancellationSlab;
import com.example.railtime.model.response.CancellationResponse.RefundRuleSet;
import com.example.railtime.properties.BookingRulesProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Nested;
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
        return cancel(TrainType.STANDARD, code, status, fare, passengers, cancelDate, cancelTime);
    }

    private CancellationResponse cancel(TrainType train, String code, TicketStatus status, String fare, int passengers,
                                        String cancelDate, String cancelTime) {
        return service.calculate(new CancellationRequest(TravelClass.fromCode(code), status, new BigDecimal(fare),
                passengers, DEPARTURE_DATE, DEPARTURE_TIME,
                cancelDate == null ? null : LocalDate.parse(cancelDate),
                cancelTime == null ? null : LocalTime.parse(cancelTime), train));
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Nested
    class StandardTrains2026 {

        @Test
        void flatChargeMoreThan72HoursBeforeWithGstOnAcClass() {
            CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-17", "18:29");

            assertThat(r.ruleSet()).isEqualTo(RefundRuleSet.STANDARD_2026);
            assertThat(r.trainType()).isEqualTo(TrainType.STANDARD);
            assertThat(r.slab()).isEqualTo(CancellationSlab.FLAT_CHARGE);
            assertThat(r.refundable()).isTrue();
            assertThat(r.minutesBeforeDeparture()).isEqualTo(72 * 60 + 1);
            assertMoney(r.farePerPassenger(), "1225.00");
            assertMoney(r.chargePerPassenger(), "180.00");
            assertMoney(r.gstPerPassenger(), "9.00");
            assertMoney(r.totalCharge(), "360.00");
            assertMoney(r.totalGst(), "18.00");
            assertMoney(r.totalRefund(), "2072.00");
            assertThat(r.slabDescription()).isEqualTo("More than 72 hours before departure: flat ₹180 per passenger");
            assertThat(r.travelClassName()).isEqualTo("Third AC");
        }

        /**
         * A real IRCTC cancellation (1 Oct 2026): 2A, confirmed, 1 passenger, departure 30 Nov 2026 18:20,
         * cancelled 1 Oct 2026 08:14. IRCTC showed Booking Amount ₹1,815.00, Railway Cancellation Charges
         * ₹210.00 and Refund Amount ₹1,605.00. (The ₹23.60 convenience fee and ₹0.45 insurance are not
         * part of the Booking Amount and are not refunded with the fare.)
         */
        @Test
        void matchesARealIrctcCancellation() {
            CancellationResponse r = service.calculate(new CancellationRequest(TravelClass.SECOND_AC, TicketStatus.CONFIRMED,
                    new BigDecimal("1815.00"), 1, LocalDate.of(2026, 11, 30), LocalTime.of(18, 20),
                    LocalDate.of(2026, 10, 1), LocalTime.of(8, 14)));

            assertThat(r.slab()).isEqualTo(CancellationSlab.FLAT_CHARGE);
            assertMoney(r.totalCharge().add(r.totalGst()), "210.00"); // ₹200 flat + ₹10 GST
            assertMoney(r.totalRefund(), "1605.00");
        }

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
                "2026-12-17, 18:29, FLAT_CHARGE",
                "2026-12-17, 18:30, QUARTER_OF_FARE",
                "2026-12-19, 18:30, QUARTER_OF_FARE",
                "2026-12-19, 18:31, HALF_OF_FARE",
                "2026-12-20, 10:30, HALF_OF_FARE",
                "2026-12-20, 10:31, NO_REFUND_CONFIRMED",
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
            assertThat(r.slabDescription()).contains("72 to 24 hours", "25% of fare", "minimum ₹180 per passenger");
        }

        @Test
        void halfOfFare() {
            CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-20", "10:00");

            assertThat(r.slab()).isEqualTo(CancellationSlab.HALF_OF_FARE);
            assertMoney(r.chargePerPassenger(), "612.50");
            assertMoney(r.gstPerPassenger(), "30.63");
            assertMoney(r.totalRefund(), "1163.74");
            assertThat(r.slabDescription()).contains("24 to 8 hours", "50% of fare");
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

        /** 6½ hours before departure: half the fare under the old rules, nothing under the 2026 rules. */
        @Test
        void noRefundWithinEightHoursKeepsTheWholeFare() {
            CancellationResponse r = cancel("3A", TicketStatus.CONFIRMED, "2450", 2, "2026-12-20", "12:00");

            assertThat(r.refundable()).isFalse();
            assertMoney(r.totalCharge(), "2450.00");
            assertMoney(r.totalGst(), "0.00");
            assertMoney(r.totalRefund(), "0.00");
            assertThat(r.slabDescription()).isEqualTo("Within 8 hours of departure: no refund on confirmed tickets");
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
        void missingTrainTypeMeansStandard() {
            CancellationRequest request = new CancellationRequest(TravelClass.SLEEPER, TicketStatus.CONFIRMED,
                    new BigDecimal("500"), 1, DEPARTURE_DATE, DEPARTURE_TIME, null, null, null);

            assertThat(request.trainType()).isEqualTo(TrainType.STANDARD);
            assertThat(service.calculate(request).ruleSet()).isEqualTo(RefundRuleSet.STANDARD_2026);
        }
    }

    /** Cancellations before 1 Apr 2026 keep the original 48 h / 12 h / 4 h slabs. Clock: 28 Sep 2026. */
    @Nested
    class StandardTrainsBeforeTheRevision {

        private CancellationResponse cancelBefore(String departure, String cancelledAt) {
            LocalDateTime dep = LocalDateTime.parse(departure);
            LocalDateTime at = LocalDateTime.parse(cancelledAt);
            return service.calculate(new CancellationRequest(TravelClass.SLEEPER, TicketStatus.CONFIRMED,
                    new BigDecimal("500"), 1, dep.toLocalDate(), dep.toLocalTime(), at.toLocalDate(), at.toLocalTime()));
        }

        @Test
        void sixtyTwoHoursBeforeIsAFlatChargeUnderThe2015Slabs() {
            CancellationResponse r = cancelBefore("2026-04-03T10:00", "2026-03-31T20:00");

            assertThat(r.ruleSet()).isEqualTo(RefundRuleSet.STANDARD_2015);
            assertThat(r.slab()).isEqualTo(CancellationSlab.FLAT_CHARGE);
            assertThat(r.slabDescription()).isEqualTo("More than 48 hours before departure: flat ₹120 per passenger");
            assertThat(r.notes()).first().asString().contains("cancellations before 1 April 2026");
        }

        @Test
        void fiftyHoursBeforeIsTwentyFivePercentOnceTheRevisionApplies() {
            CancellationResponse r = cancelBefore("2026-04-03T10:00", "2026-04-01T08:00");

            assertThat(r.ruleSet()).isEqualTo(RefundRuleSet.STANDARD_2026);
            assertThat(r.slab()).isEqualTo(CancellationSlab.QUARTER_OF_FARE);
        }

        @Test
        void fiveHoursBeforeStillRefundsHalfUnderThe2015Slabs() {
            CancellationResponse r = cancelBefore("2026-04-01T01:00", "2026-03-31T20:00");

            assertThat(r.slab()).isEqualTo(CancellationSlab.HALF_OF_FARE);
            assertThat(r.slabDescription()).contains("12 to 4 hours", "50% of fare");
            assertThat(r.notes()).anyMatch(n -> n.contains("up to 4 hours before departure"));
        }

        @Test
        void withinFourHoursIsNoRefundUnderThe2015Slabs() {
            CancellationResponse r = cancelBefore("2026-03-31T23:00", "2026-03-31T20:00");

            assertThat(r.slab()).isEqualTo(CancellationSlab.NO_REFUND_CONFIRMED);
            assertThat(r.slabDescription()).isEqualTo("Within 4 hours of departure: no refund on confirmed tickets");
        }
    }

    /** Vande Bharat Sleeper and Amrit Bharat II: G.S.R. 41(E), 16 January 2026. */
    @Nested
    class PremiumSleeperTrains {

        @Test
        void moreThan72HoursBeforeDeductsAQuarterOfTheFare() {
            CancellationResponse r = cancel(TrainType.VANDE_BHARAT_SLEEPER, "3A", TicketStatus.CONFIRMED, "3000", 1,
                    "2026-12-17", "18:29");

            assertThat(r.ruleSet()).isEqualTo(RefundRuleSet.PREMIUM_SLEEPER_2026);
            assertThat(r.trainType()).isEqualTo(TrainType.VANDE_BHARAT_SLEEPER);
            assertThat(r.slab()).isEqualTo(CancellationSlab.QUARTER_OF_FARE);
            assertMoney(r.chargePerPassenger(), "750.00");
            assertMoney(r.gstPerPassenger(), "37.50");
            assertMoney(r.totalRefund(), "2212.50");
            assertThat(r.slabDescription()).isEqualTo("More than 72 hours before departure: 25% of fare");
            assertThat(r.notes()).first().isEqualTo(CancellationRefundService.NOTE_SOURCE_PREMIUM);
        }

        @Test
        void from72HoursDownTo8HoursDeductsHalfTheFare() {
            CancellationResponse r = cancel(TrainType.VANDE_BHARAT_SLEEPER, "3A", TicketStatus.CONFIRMED, "3000", 1,
                    "2026-12-17", "18:30");

            assertThat(r.slab()).isEqualTo(CancellationSlab.HALF_OF_FARE);
            assertMoney(r.chargePerPassenger(), "1500.00");
            assertMoney(r.gstPerPassenger(), "75.00");
            assertMoney(r.totalRefund(), "1425.00");
            assertThat(r.slabDescription()).isEqualTo("72 to 8 hours before departure: 50% of fare");
        }

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
                "2026-12-17, 18:29, QUARTER_OF_FARE",
                "2026-12-17, 18:30, HALF_OF_FARE",
                "2026-12-20, 10:30, HALF_OF_FARE",
                "2026-12-20, 10:31, NO_REFUND_CONFIRMED"
        })
        void slabBoundaries(String date, String time, CancellationSlab expected) {
            assertThat(cancel(TrainType.AMRIT_BHARAT_II, "SL", TicketStatus.CONFIRMED, "800", 1, date, time).slab())
                    .isEqualTo(expected);
        }

        @Test
        void hasNoFlatMinimum() {
            CancellationResponse r = cancel(TrainType.AMRIT_BHARAT_II, "SL", TicketStatus.CONFIRMED, "200", 1,
                    "2026-12-01", "10:00");

            assertMoney(r.chargePerPassenger(), "50.00"); // 25%, below the ₹120 standard flat charge
            assertMoney(r.totalRefund(), "150.00");
        }

        @Test
        void noRefundWithinEightHours() {
            CancellationResponse r = cancel(TrainType.VANDE_BHARAT_SLEEPER, "2A", TicketStatus.CONFIRMED, "4000", 2,
                    "2026-12-20", "11:00");

            assertThat(r.refundable()).isFalse();
            assertMoney(r.totalRefund(), "0.00");
            assertThat(r.slabDescription()).isEqualTo("Within 8 hours of departure: no refund on confirmed tickets");
            assertThat(r.notes()).anyMatch(n -> n.contains("up to 8 hours before departure"));
        }

        @Test
        void tatkalAndWaitlistFollowTheCommonRules() {
            assertThat(cancel(TrainType.VANDE_BHARAT_SLEEPER, "3A", TicketStatus.CONFIRMED_TATKAL, "3000", 1,
                    "2026-12-01", "10:00").slab()).isEqualTo(CancellationSlab.NO_REFUND_TATKAL);
            CancellationResponse waitlisted = cancel(TrainType.VANDE_BHARAT_SLEEPER, "3A", TicketStatus.WAITLISTED,
                    "3000", 1, "2026-12-20", "12:00");
            assertThat(waitlisted.slab()).isEqualTo(CancellationSlab.CLERKAGE);
            assertMoney(waitlisted.chargePerPassenger(), "60.00");
        }

        @Test
        void vandeBharatSleeperHasNoRac() {
            assertThatThrownBy(() -> cancel(TrainType.VANDE_BHARAT_SLEEPER, "3A", TicketStatus.RAC, "3000", 1,
                    "2026-12-01", "10:00"))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessage(CancellationRefundService.NO_RAC_ON_VANDE_BHARAT_SLEEPER)
                    .extracting(e -> ((RuleViolationException) e).field()).isEqualTo("ticketStatus");
        }

        @Test
        void amritBharatRacPaysClerkage() {
            assertThat(cancel(TrainType.AMRIT_BHARAT_II, "SL", TicketStatus.RAC, "800", 1, "2026-12-01", "10:00").slab())
                    .isEqualTo(CancellationSlab.CLERKAGE);
        }
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
                .first().isEqualTo(CancellationRefundService.NOTE_SOURCE_2026);
        assertThat(cancel("3A", TicketStatus.CONFIRMED, "1000", 1, "2026-12-01", "10:00").notes())
                .anyMatch(n -> n.startsWith("GST (5%)"))
                .anyMatch(n -> n.contains("TDR") && n.contains("up to 8 hours"))
                .contains(CancellationRefundService.NOTE_FEES)
                .last().isEqualTo(CancellationRefundService.NOTE_VERIFY);
        assertThat(cancel("SL", TicketStatus.RAC, "1000", 1, "2026-12-01", "10:00").notes())
                .noneMatch(n -> n.startsWith("GST"))
                .noneMatch(n -> n.contains("TDR"));
    }

    @Test
    void failsFastWhenAClassHasNoFlatCharge() {
        BookingRulesProperties defaults = TestFixtures.defaultRules();
        Map<String, BigDecimal> flat = TestFixtures.flatCharges();
        flat.remove("2S");
        BookingRulesProperties broken = new BookingRulesProperties(defaults.timezone(), defaults.general(), defaults.tatkal(),
                defaults.validation(), TestFixtures.cancellationRules(flat), defaults.chart());

        assertThatThrownBy(() -> new CancellationRefundService(
                new BookingRuleService(broken, TestFixtures.fixedClock()), broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2S");
    }
}
