package com.example.railtime.config;

import com.example.railtime.properties.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs where the app can be opened once it is ready: the local dashboard (with the port actually
 * bound, e.g. Render's {@code PORT}), health check and, when enabled, the API docs. When a public
 * URL is configured (Render's {@code RENDER_EXTERNAL_URL} or {@code SITE_URL}) it is logged too.
 */
@Component
public class StartupUrlLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupUrlLogger.class);

    private final Environment environment;
    private final ApplicationProperties properties;

    public StartupUrlLogger(Environment environment, ApplicationProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logUrls() {
        // Set by Spring Boot once the embedded server has bound; absent in tests without a server.
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            return;
        }
        String local = "http://localhost:" + port + environment.getProperty("server.servlet.context-path", "");
        StringBuilder message = new StringBuilder("RailTime Calculator is ready")
                .append("\n  Dashboard : ").append(local).append('/')
                .append("\n  Health    : ").append(local).append("/actuator/health");
        if (properties.docs().enabled()) {
            message.append("\n  API docs  : ").append(local).append("/swagger-ui.html");
        }
        String publicUrl = properties.siteUrl();
        if (publicUrl != null && !publicUrl.isBlank() && !publicUrl.contains("://localhost")) {
            message.append("\n  Public URL: ").append(publicUrl);
        }
        log.info("{}", message);
    }
}
