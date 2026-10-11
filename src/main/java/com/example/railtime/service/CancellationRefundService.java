package com.example.railtime.service;

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
import com.example.railtime.util.DateTimeUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Estimates the refund on cancelling a ticket, per the Railway Passengers (Cancellation of Ticket
 * and Refund of Fare) Rules, 2015, as amended in 2026.
 *
 * <p><b>Standard trains</b>, cancelled on or after the April 2026 revision ({@link RefundRuleSet#STANDARD_2026}):
 * <ul>
 *   <li>Confirmed, more than 72 h before departure: flat charge per passenger by class.</li>
 *   <li>Confirmed, 72 h down to 24 h: 25% of fare, at least the flat charge.</li>
 *   <li>Confirmed, under 24 h down to 8 h: 50% of fare, at least the flat charge.</li>
 *   <li>Confirmed, within 8 h: no refund.</li>
 * </ul>
 * Cancellations before the revision ({@link RefundRuleSet#STANDARD_2015}) use the original
 * 48 h / 12 h / 4 h boundaries with the same charges.
 *
 * <p><b>Vande Bharat Sleeper and Amrit Bharat II</b> ({@link RefundRuleSet#PREMIUM_SLEEPER_2026},
 * G.S.R. 41(E), 16 January 2026): 25% of fare more than 72 h before departure, 50% from 72 h down
 * to 8 h, then no refund. There is no flat minimum, and Vande Bharat Sleeper has no RAC.
 *
 * <p><b>All trains:</b> confirmed Tatkal gets no refund; RAC / waitlisted tickets pay clerkage per
 * passenger up to 30 minutes before departure, then nothing. GST at the configured rate is added
 * to the charge for AC classes and First Class. Amounts are rounded to the paisa; the result is an
 * estimate and says so.
 */
@Service
public class CancellationRefundService {

    private static final Logger log = LoggerFactory.getLogger(CancellationRefundService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    static final String CANCEL_AFTER_DEPARTURE = "Cancellation time must be before the scheduled departure.";
    static final String PARTIAL_CANCELLATION_TIME = "Provide both the cancellation date and time, or leave both empty to use now.";
    static final String NO_RAC_ON_VANDE_BHARAT_SLEEPER = "Vande Bharat Sleeper trains do not have RAC. "
            + "Choose Confirmed, Confirmed Tatkal or Waitlisted.";
    static final String NOTE_SOURCE_2026 = "Estimate based on the Railway Passengers (Cancellation of Ticket and Refund "
            + "of Fare) Rules, 2015, with the refund slabs revised in April 2026.";
    static final String NOTE_SOURCE_2015 = "Estimate based on the original refund slabs of the Railway Passengers "
            + "(Cancellation of Ticket and Refund of Fare) Rules, 2015, which applied to cancellations before %s.";
    static final String NOTE_SOURCE_PREMIUM = "Estimate based on the refund scale for Vande Bharat Sleeper and "
            + "Amrit Bharat II trains (Amendment Rules, 2026; G.S.R. 41(E), 16 January 2026).";
    static final String NOTE_FEES = "IRCTC convenience fee and payment gateway charges are not refunded.";
    static final String NOTE_VERIFY = "Always check the final amount on IRCTC before cancelling.";

    private final BookingRuleService rules;
    private final BookingRulesProperties.Cancellation config;

    public CancellationRefundService(BookingRuleService rules, BookingRulesProperties properties) {
        this.rules = rules;
        this.config = properties.cancellation();
        for (TravelClass travelClass : TravelClass.values()) {
            if (!config.flatCharges().containsKey(travelClass.code())) {
                throw new IllegalStateException("booking.cancellation.flat-charges has no entry for class " + travelClass.code());
            }
        }
    }

    public CancellationResponse calculate(CancellationRequest request) {
        ZoneId zone = rules.zone();
        ZonedDateTime now = rules.now();
        rules.validateDateWindow(request.departureDate(), now.toLocalDate(), "Departure date");
        ZonedDateTime departure = ZonedDateTime.of(request.departureDate(), request.departureTime(), zone);
        ZonedDateTime cancelAt = resolveCancellationTime(request, now, zone);
        if (!cancelAt.isBefore(departure)) {
            throw new InvalidDateException(CANCEL_AFTER_DEPARTURE);
        }
        TrainType trainType = request.trainType();
        if (trainType == TrainType.VANDE_BHARAT_SLEEPER && request.ticketStatus() == TicketStatus.RAC) {
            throw new RuleViolationException("ticketStatus", NO_RAC_ON_VANDE_BHARAT_SLEEPER);
        }

        TravelClass travelClass = request.travelClass();
        Duration lead = Duration.between(cancelAt, departure);
        RefundRuleSet ruleSet = ruleSetFor(trainType, cancelAt);
        CancellationSlab slab = slabFor(request.ticketStatus(), lead, ruleSet);
        boolean refundable = isRefundable(slab);

        BigDecimal totalFare = money(request.totalFare());
        BigDecimal passengers = BigDecimal.valueOf(request.passengers());
        BigDecimal farePerPassenger = totalFare.divide(passengers, 2, RoundingMode.HALF_UP);
        BigDecimal flat = config.flatCharges().get(travelClass.code());

        BigDecimal charge = chargePerPassenger(slab, ruleSet, farePerPassenger, flat).min(farePerPassenger);
        BigDecimal gst = refundable && config.gstClasses().contains(travelClass.code())
                ? percentOf(charge, config.gstRatePercent()).min(farePerPassenger.subtract(charge))
                : BigDecimal.ZERO.setScale(2);

        BigDecimal totalCharge = refundable ? money(charge.multiply(passengers)) : totalFare;
        BigDecimal totalGst = money(gst.multiply(passengers));
        BigDecimal totalRefund = refundable ? totalFare.subtract(totalCharge).subtract(totalGst).max(BigDecimal.ZERO) : money(BigDecimal.ZERO);

        log.info("event=calculation type=CANCELLATION class={} status={} trainType={} ruleSet={} minutesBefore={} slab={} passengers={}",
                travelClass.code(), request.ticketStatus(), trainType, ruleSet, lead.toMinutes(), slab, request.passengers());

        return new CancellationResponse(
                travelClass,
                travelClass.displayName(),
                request.ticketStatus(),
                trainType,
                request.passengers(),
                departure.toOffsetDateTime(),
                cancelAt.toOffsetDateTime(),
                lead.toMinutes(),
                ruleSet,
                slab,
                describe(slab, ruleSet, flat),
                refundable,
                totalFare,
                farePerPassenger,
                charge,
                gst,
                totalCharge,
                totalGst,
                totalRefund,
                config.gstRatePercent(),
                notes(travelClass, request.ticketStatus(), ruleSet));
    }

    /** Premium sleeper trains have their own scale; standard trains switch scale on the revision date. */
    RefundRuleSet ruleSetFor(TrainType trainType, ZonedDateTime cancelAt) {
        if (trainType.premiumRefundScale()) {
            return RefundRuleSet.PREMIUM_SLEEPER_2026;
        }
        return cancelAt.toLocalDate().isBefore(config.revisedFrom()) ? RefundRuleSet.STANDARD_2015 : RefundRuleSet.STANDARD_2026;
    }

    /** Which rule applies, from ticket status, time left before departure and the refund scale. */
    CancellationSlab slabFor(TicketStatus status, Duration lead, RefundRuleSet ruleSet) {
        return switch (status) {
            case CONFIRMED_TATKAL -> CancellationSlab.NO_REFUND_TATKAL;
            case RAC, WAITLISTED -> lead.compareTo(Duration.ofMinutes(config.racWaitlistCutoffMinutes())) >= 0
                    ? CancellationSlab.CLERKAGE : CancellationSlab.NO_REFUND_RAC_WAITLIST;
            case CONFIRMED -> ruleSet == RefundRuleSet.PREMIUM_SLEEPER_2026
                    ? premiumSlab(lead) : standardSlab(lead, slabs(ruleSet));
        };
    }

    /** "More than 72 h": flat; "72 h to 24 h": 25%; "under 24 h to 8 h": 50%; then nothing. */
    private static CancellationSlab standardSlab(Duration lead, BookingRulesProperties.Slabs slabs) {
        if (lead.compareTo(Duration.ofHours(slabs.flatChargeAboveHours())) > 0) {
            return CancellationSlab.FLAT_CHARGE;
        }
        if (lead.compareTo(Duration.ofHours(slabs.quarterChargeFromHours())) >= 0) {
            return CancellationSlab.QUARTER_OF_FARE;
        }
        if (lead.compareTo(Duration.ofHours(slabs.confirmedCutoffHours())) >= 0) {
            return CancellationSlab.HALF_OF_FARE;
        }
        return CancellationSlab.NO_REFUND_CONFIRMED;
    }

    /** "More than 72 h": early rate; "between 72 h and 8 h": late rate; "less than 8 h": nothing. */
    private CancellationSlab premiumSlab(Duration lead) {
        BookingRulesProperties.PremiumSleeper premium = config.premiumSleeper();
        if (lead.compareTo(Duration.ofHours(premium.earlyAboveHours())) > 0) {
            return CancellationSlab.QUARTER_OF_FARE;
        }
        if (lead.compareTo(Duration.ofHours(premium.cutoffHours())) >= 0) {
            return CancellationSlab.HALF_OF_FARE;
        }
        return CancellationSlab.NO_REFUND_CONFIRMED;
    }

    private BookingRulesProperties.Slabs slabs(RefundRuleSet ruleSet) {
        return ruleSet == RefundRuleSet.STANDARD_2015 ? config.previous() : config.current();
    }

    private BigDecimal chargePerPassenger(CancellationSlab slab, RefundRuleSet ruleSet, BigDecimal fare, BigDecimal flat) {
        // The premium scale is percentage-only (no flat minimum); Tatkal, clerkage and no-refund are shared.
        boolean premium = ruleSet == RefundRuleSet.PREMIUM_SLEEPER_2026;
        if (premium && slab == CancellationSlab.QUARTER_OF_FARE) {
            return percentOf(fare, config.premiumSleeper().earlyRatePercent());
        }
        if (premium && slab == CancellationSlab.HALF_OF_FARE) {
            return percentOf(fare, config.premiumSleeper().lateRatePercent());
        }
        return switch (slab) {
            case FLAT_CHARGE -> money(flat);
            case QUARTER_OF_FARE -> percentOf(fare, config.quarterRatePercent()).max(money(flat));
            case HALF_OF_FARE -> percentOf(fare, config.halfRatePercent()).max(money(flat));
            case CLERKAGE -> money(config.clerkage());
            case NO_REFUND_CONFIRMED, NO_REFUND_TATKAL, NO_REFUND_RAC_WAITLIST -> fare;
        };
    }

    private String describe(CancellationSlab slab, RefundRuleSet ruleSet, BigDecimal flat) {
        int cutoff = confirmedCutoffHours(ruleSet);
        if (ruleSet == RefundRuleSet.PREMIUM_SLEEPER_2026) {
            BookingRulesProperties.PremiumSleeper premium = config.premiumSleeper();
            if (slab == CancellationSlab.QUARTER_OF_FARE) {
                return "More than %d hours before departure: %s%% of fare"
                        .formatted(premium.earlyAboveHours(), plain(premium.earlyRatePercent()));
            }
            if (slab == CancellationSlab.HALF_OF_FARE) {
                return "%d to %d hours before departure: %s%% of fare"
                        .formatted(premium.earlyAboveHours(), cutoff, plain(premium.lateRatePercent()));
            }
        }
        BookingRulesProperties.Slabs slabs = slabs(ruleSet);
        String minimum = "minimum " + rupees(flat) + " per passenger";
        return switch (slab) {
            case FLAT_CHARGE -> "More than %d hours before departure: flat %s per passenger"
                    .formatted(slabs.flatChargeAboveHours(), rupees(flat));
            case QUARTER_OF_FARE -> "%d to %d hours before departure: %s%% of fare (%s)"
                    .formatted(slabs.flatChargeAboveHours(), slabs.quarterChargeFromHours(),
                            plain(config.quarterRatePercent()), minimum);
            case HALF_OF_FARE -> "%d to %d hours before departure: %s%% of fare (%s)"
                    .formatted(slabs.quarterChargeFromHours(), slabs.confirmedCutoffHours(),
                            plain(config.halfRatePercent()), minimum);
            case CLERKAGE -> "RAC / waitlisted, %d minutes or more before departure: clerkage %s per passenger"
                    .formatted(config.racWaitlistCutoffMinutes(), rupees(config.clerkage()));
            case NO_REFUND_CONFIRMED -> "Within %d hours of departure: no refund on confirmed tickets".formatted(cutoff);
            case NO_REFUND_TATKAL -> "Confirmed Tatkal tickets: no refund on cancellation";
            case NO_REFUND_RAC_WAITLIST -> "Within %d minutes of departure: no refund on RAC / waitlisted tickets"
                    .formatted(config.racWaitlistCutoffMinutes());
        };
    }

    private int confirmedCutoffHours(RefundRuleSet ruleSet) {
        return ruleSet == RefundRuleSet.PREMIUM_SLEEPER_2026
                ? config.premiumSleeper().cutoffHours() : slabs(ruleSet).confirmedCutoffHours();
    }

    private List<String> notes(TravelClass travelClass, TicketStatus status, RefundRuleSet ruleSet) {
        List<String> notes = new ArrayList<>(5);
        notes.add(switch (ruleSet) {
            case STANDARD_2026 -> NOTE_SOURCE_2026;
            case STANDARD_2015 -> NOTE_SOURCE_2015.formatted(DateTimeUtil.formatLongDate(config.revisedFrom()));
            case PREMIUM_SLEEPER_2026 -> NOTE_SOURCE_PREMIUM;
        });
        if (config.gstClasses().contains(travelClass.code())) {
            notes.add("GST (%s%%) is levied on the cancellation charge for AC and First Class tickets."
                    .formatted(plain(config.gstRatePercent())));
        }
        if (status == TicketStatus.CONFIRMED) {
            notes.add("Confirmed e-tickets can be cancelled online up to %d hours before departure; after that, "
                    .formatted(confirmedCutoffHours(ruleSet)) + "a TDR can be filed only in specific circumstances.");
        }
        notes.add(NOTE_FEES);
        notes.add(NOTE_VERIFY);
        return List.copyOf(notes);
    }

    private static ZonedDateTime resolveCancellationTime(CancellationRequest request, ZonedDateTime now, ZoneId zone) {
        if (request.cancellationDate() == null && request.cancellationTime() == null) {
            return now;
        }
        if (request.cancellationDate() == null || request.cancellationTime() == null) {
            throw new InvalidDateException(PARTIAL_CANCELLATION_TIME);
        }
        return ZonedDateTime.of(request.cancellationDate(), request.cancellationTime(), zone);
    }

    private static boolean isRefundable(CancellationSlab slab) {
        return switch (slab) {
            case FLAT_CHARGE, QUARTER_OF_FARE, HALF_OF_FARE, CLERKAGE -> true;
            case NO_REFUND_CONFIRMED, NO_REFUND_TATKAL, NO_REFUND_RAC_WAITLIST -> false;
        };
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String rupees(BigDecimal value) {
        return "₹" + plain(value);
    }
}
