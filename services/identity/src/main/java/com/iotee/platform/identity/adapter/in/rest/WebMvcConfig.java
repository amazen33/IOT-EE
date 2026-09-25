package com.iotee.platform.identity.adapter.in.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The REST adapter's own Spring MVC wiring: registers
 * {@link CorrelationIdHandlerInterceptor} on every request.
 *
 * <p>Lives in {@code adapter.in.rest}, not {@code config}, because ADR
 * 0017 Decision 4 confines {@code org.springframework.web..} to the REST
 * adapter package; {@code config} keeps only transport-neutral wiring.
 * Before ADR 0013 this was an auto-configuration class in the retired
 * shared {@code adapters/web-spring} module; it is now a few lines this
 * service owns, per ADR 0013 Decision 6.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean
    public CorrelationIdHandlerInterceptor correlationIdHandlerInterceptor() {
        return new CorrelationIdHandlerInterceptor();
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(correlationIdHandlerInterceptor()).addPathPatterns("/**");
    }
}
