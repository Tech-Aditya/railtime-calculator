package com.example.railtime.model;

/** How a travel class is treated by the standard IRCTC Tatkal timing rules. */
public enum TatkalCategory {

    /** Listed AC Tatkal class — opens at the configured AC opening time. */
    AC,

    /** Listed non-AC Tatkal class — opens at the configured non-AC opening time. */
    NON_AC,

    /**
     * Not listed among the standard IRCTC Tatkal classes. No opening time is calculated; the
     * user is told to verify availability for the specific train instead.
     */
    NOT_LISTED
}
