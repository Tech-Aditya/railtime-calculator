package com.example.railtime.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Picks the interface language, first match wins:
 * <ol>
 *   <li>a language chosen in this request ({@code ?lang=hi}, via {@link #setLocale});</li>
 *   <li>the {@value #COOKIE} cookie from an earlier choice;</li>
 *   <li>the browser's {@code Accept-Language}, if it names a supported language;</li>
 *   <li>{@link Language#DEFAULT}.</li>
 * </ol>
 * Only supported languages are ever resolved or stored, so the page never claims a language it has
 * no translation for.
 */
public class LanguageLocaleResolver implements LocaleResolver {

    public static final String COOKIE = "railtime.lang";
    public static final String PARAM = "lang";
    private static final String REQUEST_ATTRIBUTE = LanguageLocaleResolver.class.getName() + ".LANGUAGE";
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return resolveLanguage(request).locale();
    }

    public static Language resolveLanguage(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof Language chosen) {
            return chosen;
        }
        return fromCookie(request)
                .or(() -> fromAcceptLanguage(request))
                .orElse(Language.DEFAULT);
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        Optional<Language> language = Language.of(locale);
        if (language.isEmpty()) {
            return; // unsupported: keep whatever was resolved before
        }
        request.setAttribute(REQUEST_ATTRIBUTE, language.get());
        if (response != null) {
            ResponseCookie cookie = ResponseCookie.from(COOKIE, language.get().code())
                    .path("/")
                    .maxAge(COOKIE_MAX_AGE)
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .sameSite("Lax")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
    }

    private static Optional<Language> fromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return Language.fromTag(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static Optional<Language> fromAcceptLanguage(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null) {
            return Optional.empty();
        }
        // getLocales() is ordered by the header's quality values.
        for (Locale locale : Collections.list(request.getLocales())) {
            Optional<Language> match = Language.of(locale);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }
}
