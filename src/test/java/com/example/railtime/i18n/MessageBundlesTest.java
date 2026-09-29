package com.example.railtime.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** Every language must translate every key, with the same placeholders, and nothing extra. */
class MessageBundlesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");

    private static Properties bundle(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource(name);
        assertThat(resource.exists()).as("%s exists", name).isTrue();
        return PropertiesLoaderUtils.loadProperties(new EncodedResource(resource, StandardCharsets.UTF_8));
    }

    private static Properties bundleFor(Language language) throws IOException {
        return bundle(language == Language.DEFAULT ? "messages.properties" : "messages_" + language.code() + ".properties");
    }

    private static Set<String> placeholders(String message) {
        Set<String> indexes = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(message);
        while (matcher.find()) {
            indexes.add(matcher.group(1));
        }
        return indexes;
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void translatesEveryKeyWithTheSamePlaceholders(Language language) throws IOException {
        Properties base = bundle("messages.properties");
        Properties translated = bundleFor(language);

        assertThat(translated.stringPropertyNames()).isEqualTo(base.stringPropertyNames());
        for (String key : base.stringPropertyNames()) {
            assertThat(translated.getProperty(key)).as("%s [%s]", key, language).isNotBlank();
            assertThat(placeholders(translated.getProperty(key)))
                    .as("placeholders of %s [%s]", key, language)
                    .isEqualTo(placeholders(base.getProperty(key)));
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void pageMessagesAvoidPlainApostrophes(Language language) throws IOException {
        // MessageFormat treats ' as a quote character in messages with arguments; use ’ instead.
        Properties translated = bundleFor(language);
        for (String key : translated.stringPropertyNames()) {
            assertThat(translated.getProperty(key)).as(key).doesNotContain("'");
        }
    }

    @Test
    void hindiIsReallyTranslated() throws IOException {
        Properties base = bundle("messages.properties");
        Properties hindi = bundleFor(Language.HI);

        assertThat(hindi.getProperty("intro.title")).isNotEqualTo(base.getProperty("intro.title")).containsPattern("\\p{InDevanagari}");
        assertThat(hindi.getProperty("js.btn.calculate")).isEqualTo("गणना करें");
    }
}
