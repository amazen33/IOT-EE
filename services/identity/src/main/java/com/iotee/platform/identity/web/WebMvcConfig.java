package com.iotee.platform.identity.web;

import com.iotee.platform.identity.correlation.CorrelationIdHandlerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * This service's own Spring MVC wiring: registers
 * {@link CorrelationIdHandlerInterceptor} on every request.
 *
 * <p>Before ADR 0013, this wiring was a Spring Boot auto-configuration
 * class in a separate shared {@code adapters/web-spring} module, applied
 * automatically to any service that depended on it. ADR 0013 retired
 * that shared module: this is now an ordinary, hand-written
 * {@code WebMvcConfigurer} owned by this service, exactly the way any
 * Spring Boot application wires its own interceptors. A future service
 * that needs the same interceptor authors its own equally small copy of
 * this class -- a few lines of Spring boilerplate is the accepted
 * duplication cost of not sharing a runtime wiring module (ADR 0013
 * Decision 6).
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
