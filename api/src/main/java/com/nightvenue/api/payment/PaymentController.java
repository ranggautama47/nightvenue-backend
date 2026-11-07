package com.nightvenue.api.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentWithOrder createPayment(
            @PathVariable @Positive long orderId,
            @Valid @RequestBody PaymentRequest request,
            // A4: missing or non-UUID header fails bean/type validation -> 400 before the service runs
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return payments.createPayment(orderId, request, idempotencyKey, jwt.getSubject());
    }
}
