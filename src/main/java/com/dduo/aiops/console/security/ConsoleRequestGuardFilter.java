package com.dduo.aiops.console.security;

import java.io.IOException;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

final class ConsoleRequestGuardFilter extends OncePerRequestFilter {

    private static final int MAX_LOGIN_BYTES = 4096;
    private static final Set<String> MUTATING_METHODS = Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final ConsoleSecuritySettings settings;
    private final ConsoleSessionService sessionService;
    private final ObjectMapper objectMapper;

    ConsoleRequestGuardFilter(ConsoleSecuritySettings settings, ConsoleSessionService sessionService,
            ObjectMapper objectMapper) {
        this.settings = settings;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/console/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var requestId = ConsoleHttpSupport.requestId(request);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(ConsoleHttpSupport.REQUEST_ID_HEADER, requestId);

        if (isLogin(request) && request.getContentLengthLong() > MAX_LOGIN_BYTES) {
            ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "CONSOLE_PAYLOAD_TOO_LARGE", "请求内容超过允许长度", requestId);
            return;
        }
        if (MUTATING_METHODS.contains(request.getMethod())) {
            var origin = request.getHeader(HttpHeaders.ORIGIN);
            if (!settings.allowedOrigin().equals(origin)) {
                ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.FORBIDDEN.value(),
                        "CONSOLE_ORIGIN_REJECTED", "请求来源未获授权", requestId);
                return;
            }
            if (!isLogin(request)) {
                var session = (ConsoleSessionRepository.SessionRecord) request
                        .getAttribute(ConsoleHttpSupport.SESSION_ATTRIBUTE);
                var csrf = request.getHeader(ConsoleHttpSupport.CSRF_HEADER);
                if (session == null) {
                    filterChain.doFilter(request, response);
                    return;
                }
                if (!StringUtils.hasText(csrf) || !sessionService.csrfMatches(session.id(), csrf)) {
                    ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.FORBIDDEN.value(),
                            "CONSOLE_CSRF_REJECTED", "安全令牌无效或已过期", requestId);
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isLogin(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && "/api/v1/console/session".equals(request.getRequestURI());
    }
}
