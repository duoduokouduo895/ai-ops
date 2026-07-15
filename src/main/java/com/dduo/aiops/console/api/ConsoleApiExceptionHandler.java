package com.dduo.aiops.console.api;

import com.dduo.aiops.console.security.ConsoleHttpSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = ConsoleApiController.class)
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleApiExceptionHandler.class);

    @ExceptionHandler(ConsoleResourceNotFoundException.class)
    ResponseEntity<ErrorEnvelope> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "CONSOLE_RESOURCE_NOT_FOUND", "请求的资源不存在", request);
    }

    @ExceptionHandler({ConsoleInvalidCursorException.class, ConstraintViolationException.class,
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorEnvelope> invalidRequest(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "CONSOLE_INVALID_REQUEST", "请求参数不符合约束", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unavailable(Exception exception, HttpServletRequest request) {
        LOGGER.atWarn().addKeyValue("event", "console_api_failure")
                .addKeyValue("errorType", exception.getClass().getSimpleName())
                .addKeyValue("requestId", ConsoleHttpSupport.requestId(request)).log("Console API request failed");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CONSOLE_TEMPORARILY_UNAVAILABLE",
                "控制面暂时不可用，请稍后重试", request);
    }

    private ResponseEntity<ErrorEnvelope> error(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        var requestId = ConsoleHttpSupport.requestId(request);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").header("X-Request-Id", requestId)
                .body(new ErrorEnvelope(code, message, requestId));
    }

    record ErrorEnvelope(String code, String message, String requestId) { }
}
