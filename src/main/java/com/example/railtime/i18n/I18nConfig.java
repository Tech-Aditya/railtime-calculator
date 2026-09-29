package com.example.railtime.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/** Interface language selection for the web page ({@code ?lang=hi}, remembered in a cookie). */
@Configuration(proxyBeanMethods = false)
public class I18nConfig implements WebMvcConfigurer {

    /** The bean name {@code localeResolver} is what Spring MVC and Thymeleaf look up. */
    @Bean
    LocaleResolver localeResolver() {
        return new LanguageLocaleResolver();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LanguageLocaleResolver.PARAM);
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor).addPathPatterns("/");
    }
}
