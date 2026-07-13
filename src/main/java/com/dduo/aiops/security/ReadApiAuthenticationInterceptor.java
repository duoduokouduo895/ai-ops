package com.dduo.aiops.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Protects GET Incident/report endpoints while leaving the separately-authenticated Stage 4 POST unchanged. */
@Component
public class ReadApiAuthenticationInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadApiAuthenticationInterceptor.class);

    private final ReadApiAuthenticator authenticator;

    public ReadApiAuthenticationInterceptor(ReadApiAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        var authorized = authenticator.isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (authorized) {
            LOGGER.atInfo().addKeyValue("event", "aidiag_read_api_access").addKeyValue("result", "allowed")
                    .addKeyValue("method", request.getMethod()).addKeyValue("path", request.getRequestURI())
                    .log("Read API access granted");
            return true;
        }
        LOGGER.atWarn().addKeyValue("event", "aidiag_read_api_access").addKeyValue("result", "rejected")
                .addKeyValue("method", request.getMethod()).addKeyValue("path", request.getRequestURI())
                .log("Read API access rejected");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aidiag-read-api\"");
        return false;
    }
}
