package com.example.railtime.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

/**
 * The strings app.js needs at runtime: every {@code js.*} key of {@code messages.properties},
 * resolved per language once at startup. Values are raw templates ({@code {0}} placeholders are
 * filled in the browser), because messages resolved without arguments bypass {@code MessageFormat}.
 */
@Component
public class ClientMessages {

    static final String PREFIX = "js.";
    static final String BASE_BUNDLE = "messages.properties";

    private final Map<Language, Map<String, String>> byLanguage = new EnumMap<>(Language.class);

    public ClientMessages(MessageSource messageSource) {
        Properties base = loadBaseBundle();
        for (Language language : Language.values()) {
            Map<String, String> messages = new TreeMap<>();
            for (String key : base.stringPropertyNames()) {
                if (key.startsWith(PREFIX)) {
                    messages.put(key.substring(PREFIX.length()), messageSource.getMessage(key, null, language.locale()));
                }
            }
            byLanguage.put(language, Map.copyOf(messages));
        }
    }

    public Map<String, String> forLanguage(Language language) {
        return byLanguage.get(language);
    }

    private static Properties loadBaseBundle() {
        try {
            return PropertiesLoaderUtils.loadProperties(
                    new EncodedResource(new ClassPathResource(BASE_BUNDLE), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + BASE_BUNDLE, e);
        }
    }
}
