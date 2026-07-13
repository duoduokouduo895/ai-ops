package com.dduo.aiops.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ReadApiWebConfiguration implements WebMvcConfigurer {

    private final ReadApiAuthenticationInterceptor interceptor;

    public ReadApiWebConfiguration(ReadApiAuthenticationInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/incidents", "/api/v1/incidents/**");
    }
}
