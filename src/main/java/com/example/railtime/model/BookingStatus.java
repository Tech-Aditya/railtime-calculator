package com.example.railtime.model;

/**
 * Where "now" (IST, server clock) sits relative to a calculated booking window.
 *
 * <p>The status only describes the <em>opening time</em>. It never implies that seats or
 * quota are actually available.
 */
public enum BookingStatus {

    /** The booking window opens in the future. */
    NOT_OPEN,

    /** The opening time has passed and the journey date has not — check IRCTC for availability. */
    OPEN,

    /** The origin departure date is already in the past. */
    JOURNEY_DATE_PASSED,

    /** No standard opening time applies (for example 1A under Tatkal). */
    NOT_APPLICABLE
}
