package com.example.railtime.properties;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Non-rule application settings (prefix {@code railtime}).
 *
 * @param siteUrl          public base URL, used for canonical / Open Graph links
 * @param docs             API documentation exposure
 * @param cors             cross-origin settings for the REST API
 * @param errors           error-response verbosity
 * @param rulesCacheMaxAge {@code Cache-Control: max-age} for the static rules endpoint
 */
@ConfigurationProperties(prefix = "railtime")
public record ApplicationProperties(
        @DefaultValue("http://localhost:8080") String siteUrl,
        @DefaultValue Docs docs,
        @DefaultValue Cors cors,
        @DefaultValue Errors errors,
        @DefaultValue("1h") Duration rulesCacheMaxAge) {

    /** @param enabled whether Swagger UI and the OpenAPI document are publicly reachable */
    public record Docs(@DefaultValue("false") boolean enabled) {
    }

    /** @param allowedOrigins origins allowed to call {@code /api/**}; empty means same-origin only */
    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }

    /**
     * @param includeExceptionDetail append the exception type to 500 messages (development only;
     *                               stack traces are never returned)
     */
    public record Errors(@DefaultValue("false") boolean includeExceptionDetail) {
    }
}
