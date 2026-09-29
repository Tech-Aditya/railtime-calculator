package com.example.railtime.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. Exposure is controlled by {@code railtime.docs.enabled} (see {@link SecurityConfig}). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String TAG_BOOKING = "Booking calculator";
    public static final String TAG_HEALTH = "Health";
    public static final String TAG_PLANNING = "Journey planning";
    public static final String TAG_USAGE = "Usage";

    private static final String DESCRIPTION = """
            Calculates when IRCTC booking windows open. Pure, deterministic and stateless — no \
            database, no external calls.

            **General reservation (ARP):** opens 60 days before the train's origin departure date, \
            excluding the date of journey, at 08:00 IST.

            **Tatkal:** opens 1 day before the origin departure date (journey date excluded) — \
            10:00 IST for AC classes (2A, 3A, CC, EC, 3E), 11:00 IST for non-AC classes (SL, FC, 2S). \
            1A is not listed among the standard Tatkal classes; the API returns `NOT_APPLICABLE` \
            with an explanation instead of a time.

            All values are configurable. This is an independent informational service and is not \
            affiliated with IRCTC or Indian Railways. Always verify rules on the official IRCTC website.""";

    @Bean
    OpenAPI railTimeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RailTime Calculator API")
                        .version("v1")
                        .description(DESCRIPTION)
                        .license(new License().name("MIT")))
                .externalDocs(new ExternalDocumentation()
                        .description("Official IRCTC website — the authoritative source for booking rules")
                        .url("https://www.irctc.co.in"))
                .tags(List.of(
                        new Tag().name(TAG_BOOKING).description("General reservation and Tatkal opening calculations"),
                        new Tag().name(TAG_PLANNING).description("Cancellation refund, chart timing and today's booking windows"),
                        new Tag().name(TAG_USAGE).description("Anonymous usage counts (visits, calculations)"),
                        new Tag().name(TAG_HEALTH).description("Liveness")));
    }
}
