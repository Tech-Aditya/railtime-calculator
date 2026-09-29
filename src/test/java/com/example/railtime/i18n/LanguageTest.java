package com.example.railtime.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LanguageTest {

    @ParameterizedTest
    @ValueSource(strings = {"hi", "HI", "hi-IN", "hi_IN", " hi "})
    void recognisesHindiTags(String tag) {
        assertThat(Language.fromTag(tag)).contains(Language.HI);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"fr", "ta-IN", "english"})
    void rejectsUnsupportedTags(String tag) {
        assertThat(Language.fromTag(tag)).isEmpty();
    }

    @Test
    void mapsLocales() {
        assertThat(Language.of(Locale.forLanguageTag("en-GB"))).contains(Language.EN);
        assertThat(Language.of(null)).isEmpty();
        assertThat(Language.HI.locale()).isEqualTo(Locale.forLanguageTag("hi"));
        assertThat(Language.HI.nativeName()).isEqualTo("हिन्दी");
    }

    @Test
    void resolverIgnoresUnsupportedLocalesAndStoresSupportedOnes() {
        LanguageLocaleResolver resolver = new LanguageLocaleResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setLocale(request, response, Locale.FRENCH);
        assertThat(resolver.resolveLocale(request)).isEqualTo(Language.DEFAULT.locale());
        assertThat(response.getHeader("Set-Cookie")).isNull();

        resolver.setLocale(request, response, Locale.forLanguageTag("hi-IN"));
        assertThat(resolver.resolveLocale(request)).isEqualTo(Language.HI.locale());
        assertThat(response.getHeader("Set-Cookie")).startsWith("railtime.lang=hi;").doesNotContain("Secure");
    }

    @Test
    void cookieIsSecureOnHttps() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LanguageLocaleResolver().setLocale(request, response, Locale.ENGLISH);

        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }
}
