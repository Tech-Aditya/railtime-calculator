package com.example.railtime.usage;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous usage counts for the dashboard's "Usage & traffic" section. */
@RestController
@Tag(name = OpenApiConfig.TAG_USAGE)
public class UsageController {

    private final UsageMetrics metrics;

    public UsageController(UsageMetrics metrics) {
        this.metrics = metrics;
    }

    @Operation(summary = "Anonymous usage counts",
            description = "Page visits (today, this week, this month, total) and successful calculations per "
                    + "calculator, plus a 30-day daily series. Counted in memory since the server started (see "
                    + "`since`); no personal data is collected.")
    @GetMapping(path = ApiPaths.USAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsageResponse> usage() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.snapshot());
    }
}
