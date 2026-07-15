package com.dduo.aiops.console.security;

import java.io.IOException;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public final class ConsoleHttpSupport {

    static final String COOKIE_NAME = "AIDIAG_CONSOLE_SESSION";
    static final String CSRF_HEADER = "X-AIDiag-CSRF";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String SESSION_ATTRIBUTE = ConsoleHttpSupport.class.getName() + ".session";
    private static final String REQUEST_ID_ATTRIBUTE = ConsoleHttpSupport.class.getName() + ".requestId";

    private ConsoleHttpSupport() {
    }

    public static String requestId(HttpServletRequest request) {
        var existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value) {
            return value;
        }
        var supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,128}")) {
            request.setAttribute(REQUEST_ID_ATTRIBUTE, supplied);
            return supplied;
        }
        var generated = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, generated);
        return generated;
    }

    static void writeError(ObjectMapper objectMapper, HttpServletResponse response, int status, String code,
            String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(REQUEST_ID_HEADER, requestId);
        objectMapper.writeValue(response.getOutputStream(), new ErrorEnvelope(code, message, requestId));
    }

    record ErrorEnvelope(String code, String message, String requestId) {
    }
}
