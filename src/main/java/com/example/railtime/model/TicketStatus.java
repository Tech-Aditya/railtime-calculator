package com.example.railtime.model;

/** Reservation status of a ticket being cancelled; decides which refund rule applies. */
public enum TicketStatus {

    /** Confirmed (general quota): flat charge / 25% / 50% slabs, no refund within the cut-off. */
    CONFIRMED,

    /** Confirmed Tatkal: no refund on cancellation. */
    CONFIRMED_TATKAL,

    /** Reservation Against Cancellation: clerkage only, up to the RAC / waitlist cut-off. */
    RAC,

    /** Waitlisted (including waitlisted Tatkal): clerkage only, up to the RAC / waitlist cut-off. */
    WAITLISTED
}
