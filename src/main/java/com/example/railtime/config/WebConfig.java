package com.example.railtime.config;

import com.example.railtime.controller.Page;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * HTTP caching. Cacheable GET responses (the page and the rules document) get a content-hash
 * {@code ETag}, so repeat visits are answered with {@code 304 Not Modified}. Static assets are
 * fingerprinted and cached long-term via {@code spring.web.resources.*} instead. Calculation
 * responses are time-dependent and sent with {@code Cache-Control: no-store}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        // Tomcat refuses to gzip responses carrying a strong ETag; weak ETags keep compression on.
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(Page.PATHS);
        registration.addUrlPatterns(ApiPaths.BOOKING + ApiPaths.RULES, ApiPaths.PUBLIC + "/*");
        registration.setName("etagFilter");
        return registration;
    }

    /** First in line, so security rejections and errors are logged with the request id too. */
    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("requestLoggingFilter");
        return registration;
    }
}
