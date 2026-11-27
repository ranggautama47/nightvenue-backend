package com.nightvenue.api.chat;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class ChatApiExceptionHandler {

    @ExceptionHandler(ChatServiceUnavailable.class)
    ProblemDetail chatServiceUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Chat service temporarily unavailable");
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create("about:blank"));
        String traceId = MDC.get("X-Request-Id");
        if (traceId != null && !traceId.isBlank()) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
