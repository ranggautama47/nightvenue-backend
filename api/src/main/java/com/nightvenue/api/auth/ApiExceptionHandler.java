package com.nightvenue.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

import static com.nightvenue.api.auth.AuthExceptions.*;

@RestControllerAdvice
public class ApiExceptionHandler {
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

    @ExceptionHandler(RateLimitExceeded.class)
    ProblemDetail rateLimitExceeded(RateLimitExceeded exception, HttpServletResponse response) {
        response.setHeader("Retry-After", Long.toString(exception.retryAfterSeconds()));
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
    }

    @ExceptionHandler(RedisUnavailable.class)
    ProblemDetail redisUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Authentication service unavailable");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        String traceId = MDC.get("X-Request-Id");
        if (traceId != null && !traceId.isBlank()) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
