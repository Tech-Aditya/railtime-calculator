package com.example.railtime.model;

/**
 * Train service type, where it changes the refund rules.
 *
 * <p>The Railway Passengers (Cancellation of Ticket and Refund of Fare) Amendment Rules, 2026
 * (G.S.R. 41(E), 16 January 2026) give Vande Bharat Sleeper Express and the reserved classes of
 * Amrit Bharat II Express a stricter, percentage-only refund scale. All other trains follow the
 * standard scale.
 */
public enum TrainType {

    /** Mail / Express, Rajdhani, Shatabdi, Vande Bharat chair car and all other trains. */
    STANDARD,

    /** Vande Bharat Sleeper Express: percentage-only refund scale; RAC is not offered. */
    VANDE_BHARAT_SLEEPER,

    /** Amrit Bharat II Express (reserved classes): same refund scale as Vande Bharat Sleeper. */
    AMRIT_BHARAT_II;

    /** Whether the stricter premium-sleeper refund scale applies. */
    public boolean premiumRefundScale() {
        return this != STANDARD;
    }
}
