package com.example.railtime.controller;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.config.OpenApiConfig;
import com.example.railtime.model.response.HealthResponse;
import com.example.railtime.service.BookingRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal public liveness endpoint. Detailed health (for orchestrators) is served by Actuator at
 * {@code /actuator/health}.
 */
@RestController
@RequestMapping(path = ApiPaths.PUBLIC, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = OpenApiConfig.TAG_HEALTH)
public class HealthController {

    private static final String APPLICATION_NAME = "RailTime Calculator";
    private static final String STATUS_UP = "UP";
    private static final String UNKNOWN_VERSION = "dev";

    private final BookingRuleService ruleService;
    private final String version;

    public HealthController(BookingRuleService ruleService, ObjectProvider<BuildProperties> buildProperties) {
        this.ruleService = ruleService;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : UNKNOWN_VERSION;
    }

    @Operation(summary = "Liveness check")
    @GetMapping(ApiPaths.HEALTH)
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new HealthResponse(STATUS_UP, APPLICATION_NAME, version, ruleService.now().toOffsetDateTime()));
    }
}
