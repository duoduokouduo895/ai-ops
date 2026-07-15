package com.dduo.aiops.console.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class ConsoleSessionAuthenticationFilter extends OncePerRequestFilter {

    private final ConsoleSessionService sessionService;

    ConsoleSessionAuthenticationFilter(ConsoleSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/console/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var rawToken = cookie(request, ConsoleHttpSupport.COOKIE_NAME);
        sessionService.authenticate(rawToken).ifPresent(session -> {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(session.principal(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_CONSOLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(ConsoleHttpSupport.SESSION_ATTRIBUTE, session);
        });
        filterChain.doFilter(request, response);
    }

    private static String cookie(HttpServletRequest request, String name) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
