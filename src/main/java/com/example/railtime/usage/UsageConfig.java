package com.example.railtime.usage;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.controller.Page;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Counts visits on the page paths and calculations on the four calculate endpoints. */
@Configuration(proxyBeanMethods = false)
public class UsageConfig implements WebMvcConfigurer {

    private final UsageMetrics metrics;

    public UsageConfig(UsageMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UsageTrackingInterceptor(metrics))
                .addPathPatterns(Page.PATHS)
                .addPathPatterns(UsageTrackingInterceptor.CALCULATIONS.keySet().toArray(String[]::new))
                .excludePathPatterns(ApiPaths.USAGE);
    }
}
