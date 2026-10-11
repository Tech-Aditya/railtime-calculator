package com.example.railtime.config;

import com.example.railtime.controller.Page;
import com.example.railtime.properties.ApplicationProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security.
 *
 * <p><b>Design decision.</b> The calculator is a public, anonymous utility: it stores no user data
 * and every calculation is a pure function of its input. The API is therefore exposed without
 * authentication, but <em>deny-by-default</em>: only the explicitly listed endpoints and methods
 * are reachable, everything else (including unlisted actuator endpoints) returns 403. The app is
 * stateless (no HTTP session), and CSRF protection is skipped only for the JSON API, which uses
 * no cookies or ambient credentials. Strict security headers (CSP, frame denial, referrer and
 * permissions policies, HSTS on HTTPS) apply to every response.
 *
 * <p><b>Extending with JWT.</b> To protect an endpoint later, add
 * {@code .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))} to {@link #applicationChain},
 * move that endpoint from {@code permitAll()} to {@code authenticated()}, and add the
 * {@code spring-boot-starter-oauth2-resource-server} dependency. Nothing else needs rewriting.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String APP_CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' data:",
            "connect-src 'self'",
            "manifest-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** Swagger UI injects inline styles, so its CSP is relaxed for styles only. */
    private static final String DOCS_CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'");

    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    private static final String[] ACTUATOR_PUBLIC = {"/actuator/health", "/actuator/health/**", "/actuator/info"};

    private final ApplicationProperties properties;
    private final JsonSecurityErrorHandler errorHandler;

    public SecurityConfig(ApplicationProperties properties, JsonSecurityErrorHandler errorHandler) {
        this.properties = properties;
        this.errorHandler = errorHandler;
    }

    /** OpenAPI / Swagger UI — public only when {@code railtime.docs.enabled=true}. */
    @Bean
    @Order(1)
    SecurityFilterChain docsChain(HttpSecurity http) throws Exception {
        http.securityMatcher(ApiPaths.DOCS)
                .authorizeHttpRequests(auth -> {
                    if (properties.docs().enabled()) {
                        auth.anyRequest().permitAll();
                    } else {
                        auth.anyRequest().denyAll();
                    }
                })
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(DOCS_CSP)));
        return applyCommon(http).build();
    }

    /** Web page, static assets, public health and the booking API. */
    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ApiPaths.STATIC_RESOURCES).permitAll()
                        .requestMatchers(HttpMethod.GET, Page.PATHS).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiPaths.PUBLIC + "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiPaths.USAGE).permitAll()
                        .requestMatchers(HttpMethod.GET, ACTUATOR_PUBLIC).permitAll()
                        // HEAD wherever pages and health are public: uptime monitors (often used to keep a
                        // free Render instance awake) probe with HEAD; MVC answers it from the GET handler.
                        .requestMatchers(HttpMethod.HEAD, Page.PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, ApiPaths.PUBLIC + "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, ACTUATOR_PUBLIC).permitAll()
                        // Public calculator API. The MVC layer enforces methods (405) and paths (404).
                        .requestMatchers(ApiPaths.BOOKING + "/**").permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .cors(Customizer.withDefaults())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(APP_CSP)));
        return applyCommon(http).build();
    }

    /**
     * The app has no user accounts. An empty store stops Spring Boot from creating a default user
     * with a generated password (and logging that password at startup).
     */
    @Bean
    UserDetailsService noUserAccounts() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.cors().allowedOrigins();
        if (!origins.isEmpty()) {
            CorsConfiguration api = new CorsConfiguration();
            api.setAllowedOrigins(origins);
            api.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name()));
            api.setAllowedHeaders(List.of("Content-Type", "Accept"));
            api.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", api);
        }
        return source;
    }

    private HttpSecurity applyCommon(HttpSecurity http) throws Exception {
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)));
    }
}
