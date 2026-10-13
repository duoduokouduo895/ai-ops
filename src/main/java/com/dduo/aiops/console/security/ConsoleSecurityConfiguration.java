package com.dduo.aiops.console.security;

import java.util.List;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class ConsoleSecurityConfiguration {

    @Bean
    @ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'session'")
    PasswordEncoder consolePasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'session'")
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
    @ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'access'")
    JwtDecoder consoleAccessJwtDecoder(ConsoleSecuritySettings settings, Clock clock) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(3000);
        // Nimbus caches JWKS and refreshes on key rotation. The URL is controlled configuration.
        var decoder = NimbusJwtDecoder.withJwkSetUri(settings.accessIssuer() + "/cdn-cgi/access/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(requests)).build();
        decoder.setJwtValidator(ConsoleAccessTokenValidator.create(settings, clock));
        return decoder;
    }

    @Bean
    @ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'access'")
    SecurityFilterChain consoleAccessSecurityFilterChain(HttpSecurity http,
            @Qualifier("consoleAccessJwtDecoder") JwtDecoder decoder, ObjectMapper objectMapper,
            @Qualifier("consoleCorsConfigurationSource") CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable()) // All Access console endpoints are read-only.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/console/**").authenticated().anyRequest().permitAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        ConsoleHttpSupport.writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "CONSOLE_ACCESS_REQUIRED", "请通过 Cloudflare 账号登录", ConsoleHttpSupport.requestId(request))))
                .addFilterBefore(new ConsoleAccessAuthenticationFilter(decoder, objectMapper), CorsFilter.class)
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
        configuration.setAllowedMethods(settings.accessMode()
                ? List.of("GET", "HEAD", "OPTIONS") : List.of("GET", "POST", "DELETE", "OPTIONS"));
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
