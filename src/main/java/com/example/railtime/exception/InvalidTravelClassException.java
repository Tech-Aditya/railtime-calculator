package com.example.railtime.exception;

import com.example.railtime.model.TravelClass;

/** An unknown or blank travel-class code. */
public class InvalidTravelClassException extends RuntimeException {

    private static final int MAX_ECHO_LENGTH = 10;

    public InvalidTravelClassException(String rejectedCode) {
        super("Invalid travel class '" + sanitize(rejectedCode) + "'. Allowed values: "
                + TravelClass.allowedCodes());
    }

    /** Echo only a short, printable prefix of user input back in error messages. */
    private static String sanitize(String code) {
        if (code == null) {
            return "";
        }
        String printable = code.replaceAll("[^\\p{Alnum} ]", "");
        return printable.length() > MAX_ECHO_LENGTH ? printable.substring(0, MAX_ECHO_LENGTH) : printable;
    }
}
