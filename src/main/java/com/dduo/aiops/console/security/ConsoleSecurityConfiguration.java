package com.dduo.aiops.console.security;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
public class ConsoleSecurityConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
    PasswordEncoder consolePasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
    SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http, ConsoleSessionService sessionService,
            ConsoleSecuritySettings settings, ObjectMapper objectMapper,
            @Qualifier("consoleCorsConfigurationSource") CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        var authenticationFilter = new ConsoleSessionAuthenticationFilter(sessionService);
        var requestGuardFilter = new ConsoleRequestGuardFilter(settings, sessionService, objectMapper);
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/console/session").permitAll()
                        .requestMatchers("/api/v1/console/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ConsoleHttpSupport.writeError(
                                objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "CONSOLE_SESSION_REQUIRED", "登录状态无效或已过期", ConsoleHttpSupport.requestId(request)))
                        .accessDeniedHandler((request, response, exception) -> ConsoleHttpSupport.writeError(
                                objectMapper, response, HttpServletResponse.SC_FORBIDDEN,
                                "CONSOLE_ACCESS_DENIED", "当前会话无权执行该操作", ConsoleHttpSupport.requestId(request))))
                .addFilterBefore(authenticationFilter, CorsFilter.class)
                .addFilterAfter(requestGuardFilter, ConsoleSessionAuthenticationFilter.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain consoleDisabledBaselineFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
    CorsConfigurationSource consoleCorsConfigurationSource(ConsoleSecuritySettings settings) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(settings.allowedOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, ConsoleHttpSupport.CSRF_HEADER,
                ConsoleHttpSupport.REQUEST_ID_HEADER));
        configuration.setExposedHeaders(List.of(ConsoleHttpSupport.REQUEST_ID_HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/console/**", configuration);
        return source;
    }
}
