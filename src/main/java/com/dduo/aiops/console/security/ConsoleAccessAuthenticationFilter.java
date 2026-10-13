package com.dduo.aiops.console.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Trust only the signed Access application token, never the email header or an old session cookie. */
final class ConsoleAccessAuthenticationFilter extends OncePerRequestFilter {
    static final String JWT_HEADER = "Cf-Access-Jwt-Assertion";
    static final String JWT_ATTRIBUTE = ConsoleAccessAuthenticationFilter.class.getName() + ".jwt";

    private final JwtDecoder decoder;
    private final ObjectMapper objectMapper;

    ConsoleAccessAuthenticationFilter(JwtDecoder decoder, ObjectMapper objectMapper) {
        this.decoder = decoder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/console/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(ConsoleHttpSupport.REQUEST_ID_HEADER, ConsoleHttpSupport.requestId(request));
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        var tokens = Collections.list(request.getHeaders(JWT_HEADER));
        if (tokens.size() != 1 || tokens.getFirst().isBlank() || tokens.getFirst().length() > 16384) {
            reject(request, response);
            return;
        }
        try {
            var jwt = decoder.decode(tokens.getFirst());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(jwt.getClaimAsString("email"),
                    null, List.of(new SimpleGrantedAuthority("ROLE_CONSOLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(JWT_ATTRIBUTE, jwt);
        } catch (JwtException | IllegalArgumentException exception) {
            // Neither credentials nor decoder details are sent to clients or logs.
            reject(request, response);
            return;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, "GET, HEAD, OPTIONS");
            ConsoleHttpSupport.writeError(objectMapper, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "CONSOLE_ACCESS_READ_ONLY", "控制台仅支持读取，请通过 Cloudflare 退出登录",
                    ConsoleHttpSupport.requestId(request));
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ConsoleHttpSupport.writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                "CONSOLE_ACCESS_REQUIRED", "请通过 Cloudflare 账号登录", ConsoleHttpSupport.requestId(request));
    }
}
