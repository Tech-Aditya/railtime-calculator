package com.example.railtime.model;

import com.example.railtime.exception.InvalidTravelClassException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Indian Railways travel classes supported by the Tatkal calculator.
 *
 * <p>The Tatkal category of each class follows the classes documented by IRCTC for Tatkal
 * booking: AC — 2A, 3A, CC, EC, 3E; non-AC — SL, FC, 2S. First AC (1A) is deliberately
 * modelled as {@link TatkalCategory#NOT_LISTED} rather than being given an invented time.
 *
 * <p>Serialised to/from JSON using the short IRCTC code (for example {@code "SL"} or {@code "3A"}).
 */
public enum TravelClass {

    SLEEPER("SL", "Sleeper", TatkalCategory.NON_AC),
    FIRST_AC("1A", "First AC", TatkalCategory.NOT_LISTED),
    SECOND_AC("2A", "Second AC", TatkalCategory.AC),
    THIRD_AC("3A", "Third AC", TatkalCategory.AC),
    AC_CHAIR_CAR("CC", "AC Chair Car", TatkalCategory.AC),
    EXECUTIVE_CHAIR_CAR("EC", "Executive Chair Car", TatkalCategory.AC),
    AC_THREE_ECONOMY("3E", "AC 3 Economy", TatkalCategory.AC),
    FIRST_CLASS("FC", "First Class", TatkalCategory.NON_AC),
    SECOND_SITTING("2S", "Second Sitting", TatkalCategory.NON_AC);

    private static final Map<String, TravelClass> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TravelClass::code, Function.identity()));

    private final String code;
    private final String displayName;
    private final TatkalCategory tatkalCategory;

    TravelClass(String code, String displayName, TatkalCategory tatkalCategory) {
        this.code = code;
        this.displayName = displayName;
        this.tatkalCategory = tatkalCategory;
    }

    /**
     * Resolves a class from its IRCTC code, case-insensitively and ignoring surrounding spaces.
     *
     * @throws InvalidTravelClassException if the code is blank or unknown
     */
    @JsonCreator
    public static TravelClass fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidTravelClassException(code);
        }
        TravelClass travelClass = BY_CODE.get(code.strip().toUpperCase(Locale.ROOT));
        if (travelClass == null) {
            throw new InvalidTravelClassException(code);
        }
        return travelClass;
    }

    /** Comma-separated list of accepted codes, for error messages and documentation. */
    public static String allowedCodes() {
        return Arrays.stream(values()).map(TravelClass::code).collect(Collectors.joining(", "));
    }

    @JsonValue
    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** Human label such as {@code "Sleeper (SL)"}. */
    public String label() {
        return displayName + " (" + code + ")";
    }

    public TatkalCategory tatkalCategory() {
        return tatkalCategory;
    }
}
