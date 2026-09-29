package com.example.railtime.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * Interface languages. Adding a language = a constant here plus {@code messages_<code>.properties}
 * with every key of {@code messages.properties} (a test enforces completeness).
 *
 * <p>The web page is localized; the REST API stays in English (it is a developer interface).
 */
public enum Language {

    // English: short month from US data ("Sep", not "Sept"); Hindi: full month names (no awkward abbreviations).
    // app.js formats dates the same way, so server- and browser-rendered dates match.
    EN("en", "English", "EN", "en_IN", "en-IN", Locale.US, "d MMM yyyy"),
    HI("hi", "हिन्दी", "हि", "hi_IN", "hi-IN", Locale.forLanguageTag("hi-IN"), "d MMMM yyyy");

    public static final Language DEFAULT = EN;

    private final String code;
    private final String nativeName;
    private final String shortLabel;
    private final String ogLocale;
    private final String intlLocale;
    private final Locale dateLocale;
    private final String mediumDatePattern;

    Language(String code, String nativeName, String shortLabel, String ogLocale, String intlLocale,
             Locale dateLocale, String mediumDatePattern) {
        this.code = code;
        this.nativeName = nativeName;
        this.shortLabel = shortLabel;
        this.ogLocale = ogLocale;
        this.intlLocale = intlLocale;
        this.dateLocale = dateLocale;
        this.mediumDatePattern = mediumDatePattern;
    }

    /** Locale for month names in dates such as "27 Nov 2026" / "27 नवंबर 2026". */
    public Locale dateLocale() {
        return dateLocale;
    }

    /** {@link java.time.format.DateTimeFormatter} pattern for compact dates. */
    public String mediumDatePattern() {
        return mediumDatePattern;
    }

    /** ISO 639-1 code, used in {@code ?lang=}, the cookie and {@code <html lang>}. */
    public String code() {
        return code;
    }

    /** The language's name in its own script, as shown in the switcher. */
    public String nativeName() {
        return nativeName;
    }

    /** Compact label for the header button. */
    public String shortLabel() {
        return shortLabel;
    }

    /** Open Graph locale, e.g. {@code hi_IN}. */
    public String ogLocale() {
        return ogLocale;
    }

    /** BCP 47 tag the browser's {@code Intl} APIs format dates with. */
    public String intlLocale() {
        return intlLocale;
    }

    public Locale locale() {
        return Locale.forLanguageTag(code);
    }

    /** Matches on the language part only, so {@code hi-IN} and {@code HI} both resolve to Hindi. */
    public static Optional<Language> fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        return of(Locale.forLanguageTag(tag.trim().replace('_', '-')));
    }

    public static Optional<Language> of(Locale locale) {
        if (locale == null) {
            return Optional.empty();
        }
        String language = locale.getLanguage();
        for (Language candidate : values()) {
            if (candidate.code.equalsIgnoreCase(language)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
