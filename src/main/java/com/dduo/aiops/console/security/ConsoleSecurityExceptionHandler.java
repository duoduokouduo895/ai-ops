package com.dduo.aiops.console.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = ConsoleSessionController.class)
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleSecurityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleSecurityExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public ConsoleSecurityExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ConsoleLoginRejectedException.class)
    void loginRejected(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.UNAUTHORIZED.value(),
                "CONSOLE_LOGIN_REJECTED", "账号或密码错误，或登录暂时受限", ConsoleHttpSupport.requestId(request));
    }

    @ExceptionHandler(ConsoleSessionExpiredException.class)
    void sessionExpired(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.UNAUTHORIZED.value(),
                "CONSOLE_SESSION_EXPIRED", "登录状态无效或已过期", ConsoleHttpSupport.requestId(request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    void invalidRequest(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.BAD_REQUEST.value(),
                "CONSOLE_INVALID_REQUEST", "请求参数不符合约束", ConsoleHttpSupport.requestId(request));
    }

    @ExceptionHandler(Exception.class)
    void unavailable(Exception exception, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        LOGGER.atWarn().addKeyValue("event", "console_session_failure")
                .addKeyValue("errorType", exception.getClass().getSimpleName())
                .addKeyValue("requestId", ConsoleHttpSupport.requestId(request))
                .log("Console session request failed");
        ConsoleHttpSupport.writeError(objectMapper, response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                "CONSOLE_TEMPORARILY_UNAVAILABLE", "控制面暂时不可用，请稍后重试",
                ConsoleHttpSupport.requestId(request));
    }
}
