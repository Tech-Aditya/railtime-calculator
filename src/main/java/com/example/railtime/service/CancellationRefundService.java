package com.example.railtime.service;

import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.TicketStatus;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.CancellationRequest;
import com.example.railtime.model.response.CancellationResponse;
import com.example.railtime.model.response.CancellationResponse.CancellationSlab;
import com.example.railtime.properties.BookingRulesProperties;
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
 * Estimates the refund on cancelling a ticket, per the Railway Passengers (Cancellation of Tickets
 * and Refund of Fares) Rules, 2015 as published by IRCTC:
 * <ul>
 *   <li>Confirmed, more than 48 h before departure: flat charge per passenger by class.</li>
 *   <li>Confirmed, 48 h down to 12 h: 25% of fare, at least the flat charge.</li>
 *   <li>Confirmed, under 12 h down to 4 h: 50% of fare, at least the flat charge.</li>
 *   <li>Confirmed, within 4 h: no refund (TDR only in specific circumstances).</li>
 *   <li>Confirmed Tatkal: no refund.</li>
 *   <li>RAC / waitlisted (incl. Tatkal): clerkage per passenger up to 30 min before departure, then no refund.</li>
 * </ul>
 * GST at the configured rate is added to the charge for AC classes and First Class. Amounts are
 * rounded to the paisa; the result is an estimate and says so.
 */
@Service
public class CancellationRefundService {

    private static final Logger log = LoggerFactory.getLogger(CancellationRefundService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    static final String CANCEL_AFTER_DEPARTURE = "Cancellation time must be before the scheduled departure.";
    static final String PARTIAL_CANCELLATION_TIME = "Provide both the cancellation date and time, or leave both empty to use now.";
    static final String NOTE_SOURCE = "Estimate based on the Railway Passengers (Cancellation of Tickets and Refund "
            + "of Fares) Rules, 2015, as published by IRCTC.";
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

        TravelClass travelClass = request.travelClass();
        Duration lead = Duration.between(cancelAt, departure);
        CancellationSlab slab = slabFor(request.ticketStatus(), lead);
        boolean refundable = isRefundable(slab);

        BigDecimal totalFare = money(request.totalFare());
        BigDecimal passengers = BigDecimal.valueOf(request.passengers());
        BigDecimal farePerPassenger = totalFare.divide(passengers, 2, RoundingMode.HALF_UP);
        BigDecimal flat = config.flatCharges().get(travelClass.code());

        BigDecimal charge = chargePerPassenger(slab, farePerPassenger, flat).min(farePerPassenger);
        BigDecimal gst = refundable && config.gstClasses().contains(travelClass.code())
                ? percentOf(charge, config.gstRatePercent()).min(farePerPassenger.subtract(charge))
                : BigDecimal.ZERO.setScale(2);

        BigDecimal totalCharge = refundable ? money(charge.multiply(passengers)) : totalFare;
        BigDecimal totalGst = money(gst.multiply(passengers));
        BigDecimal totalRefund = refundable ? totalFare.subtract(totalCharge).subtract(totalGst).max(BigDecimal.ZERO) : money(BigDecimal.ZERO);

        log.info("event=calculation type=CANCELLATION class={} status={} minutesBefore={} slab={} passengers={}",
                travelClass.code(), request.ticketStatus(), lead.toMinutes(), slab, request.passengers());

        return new CancellationResponse(
                travelClass,
                travelClass.displayName(),
                request.ticketStatus(),
                request.passengers(),
                departure.toOffsetDateTime(),
                cancelAt.toOffsetDateTime(),
                lead.toMinutes(),
                slab,
                describe(slab, flat),
                refundable,
                totalFare,
                farePerPassenger,
                charge,
                gst,
                totalCharge,
                totalGst,
                totalRefund,
                config.gstRatePercent(),
                notes(travelClass, request.ticketStatus()));
    }

    /** Which rule applies, from ticket status and time left before departure. */
    CancellationSlab slabFor(TicketStatus status, Duration lead) {
        return switch (status) {
            case CONFIRMED_TATKAL -> CancellationSlab.NO_REFUND_TATKAL;
            case RAC, WAITLISTED -> lead.compareTo(Duration.ofMinutes(config.racWaitlistCutoffMinutes())) >= 0
                    ? CancellationSlab.CLERKAGE : CancellationSlab.NO_REFUND_RAC_WAITLIST;
            case CONFIRMED -> {
                if (lead.compareTo(Duration.ofHours(config.flatChargeAboveHours())) > 0) {
                    yield CancellationSlab.FLAT_CHARGE;
                }
                if (lead.compareTo(Duration.ofHours(config.quarterChargeFromHours())) >= 0) {
                    yield CancellationSlab.QUARTER_OF_FARE;
                }
                if (lead.compareTo(Duration.ofHours(config.confirmedCutoffHours())) >= 0) {
                    yield CancellationSlab.HALF_OF_FARE;
                }
                yield CancellationSlab.NO_REFUND_CONFIRMED;
            }
        };
    }

    private BigDecimal chargePerPassenger(CancellationSlab slab, BigDecimal fare, BigDecimal flat) {
        return switch (slab) {
            case FLAT_CHARGE -> money(flat);
            case QUARTER_OF_FARE -> percentOf(fare, config.quarterRatePercent()).max(money(flat));
            case HALF_OF_FARE -> percentOf(fare, config.halfRatePercent()).max(money(flat));
            case CLERKAGE -> money(config.clerkage());
            case NO_REFUND_CONFIRMED, NO_REFUND_TATKAL, NO_REFUND_RAC_WAITLIST -> fare;
        };
    }

    private String describe(CancellationSlab slab, BigDecimal flat) {
        String minimum = "minimum " + rupees(flat) + " per passenger";
        return switch (slab) {
            case FLAT_CHARGE -> "More than %d hours before departure: flat %s per passenger"
                    .formatted(config.flatChargeAboveHours(), rupees(flat));
            case QUARTER_OF_FARE -> "%d to %d hours before departure: %s%% of fare (%s)"
                    .formatted(config.flatChargeAboveHours(), config.quarterChargeFromHours(),
                            plain(config.quarterRatePercent()), minimum);
            case HALF_OF_FARE -> "%d to %d hours before departure: %s%% of fare (%s)"
                    .formatted(config.quarterChargeFromHours(), config.confirmedCutoffHours(),
                            plain(config.halfRatePercent()), minimum);
            case CLERKAGE -> "RAC / waitlisted, %d minutes or more before departure: clerkage %s per passenger"
                    .formatted(config.racWaitlistCutoffMinutes(), rupees(config.clerkage()));
            case NO_REFUND_CONFIRMED -> "Within %d hours of departure: no refund on confirmed tickets"
                    .formatted(config.confirmedCutoffHours());
            case NO_REFUND_TATKAL -> "Confirmed Tatkal tickets: no refund on cancellation";
            case NO_REFUND_RAC_WAITLIST -> "Within %d minutes of departure: no refund on RAC / waitlisted tickets"
                    .formatted(config.racWaitlistCutoffMinutes());
        };
    }

    private List<String> notes(TravelClass travelClass, TicketStatus status) {
        List<String> notes = new ArrayList<>(5);
        notes.add(NOTE_SOURCE);
        if (config.gstClasses().contains(travelClass.code())) {
            notes.add("GST (%s%%) is levied on the cancellation charge for AC and First Class tickets."
                    .formatted(plain(config.gstRatePercent())));
        }
        if (status == TicketStatus.CONFIRMED) {
            notes.add("Confirmed e-tickets can be cancelled online up to %d hours before departure; after that, "
                    .formatted(config.confirmedCutoffHours()) + "a TDR can be filed only in specific circumstances.");
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
