package com.nightvenue.api.common;

import com.nightvenue.api.auth.AuthExceptions.InvalidAccessToken;
import com.nightvenue.api.auth.AuthExceptions.InvalidCredentials;
import com.nightvenue.api.auth.AuthExceptions.InvalidRefreshToken;
import com.nightvenue.api.auth.AuthExceptions.RateLimitExceeded;
import com.nightvenue.api.auth.AuthExceptions.RedisUnavailable;
import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.sql.SQLException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidCredentials.class)
    ProblemDetail invalidCredentials() {
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    @ExceptionHandler(InvalidRefreshToken.class)
    ProblemDetail invalidRefreshToken() {
        return problem(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }

    @ExceptionHandler(InvalidAccessToken.class)
    ProblemDetail invalidAccessToken() {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @ExceptionHandler(InvalidRequest.class)
    ProblemDetail invalidRequest(InvalidRequest exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(VenueNotFound.class)
    ProblemDetail venueNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Venue not found");
    }

    @ExceptionHandler(ResourceNotFound.class)
    ProblemDetail resourceNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(RateLimitExceeded.class)
    ProblemDetail rateLimitExceeded(RateLimitExceeded exception, HttpServletResponse response) {
        response.setHeader("Retry-After", Long.toString(exception.retryAfterSeconds()));
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
    }

    @ExceptionHandler(RedisUnavailable.class)
    ProblemDetail redisUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Authentication service unavailable");
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail invalidPayload(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed");
    }

    @ExceptionHandler(BusinessRuleViolation.class)
    ProblemDetail businessRuleViolation(BusinessRuleViolation exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }

    @ExceptionHandler(Conflict.class)
    ProblemDetail conflict(Conflict exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrityViolation(DataIntegrityViolationException exception) {
        String sqlState = sqlState(exception);
        if ("23505".equals(sqlState)) {
            return problem(HttpStatus.CONFLICT, "The request conflicts with existing data");
        }
        String traceId = MDC.get("X-Request-Id");
        log.error("Unexpected data integrity failure exceptionClass={} sqlState={} traceId={}",
                exception.getClass().getName(), sqlState, traceId);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private static String sqlState(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        return "unknown";
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create("about:blank"));
        String traceId = MDC.get("X-Request-Id");
        if (traceId != null && !traceId.isBlank()) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
