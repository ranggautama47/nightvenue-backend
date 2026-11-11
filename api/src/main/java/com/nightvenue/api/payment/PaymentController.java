package com.nightvenue.api.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final QrisPaymentCreator qrisPayments;

    public PaymentController(PaymentService payments, QrisPaymentCreator qrisPayments) {
        this.payments = payments;
        this.qrisPayments = qrisPayments;
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<?> createPayment(
            @PathVariable @Positive long orderId,
            @Valid @RequestBody PaymentRequest request,
            // A4: missing or non-UUID header fails bean/type validation -> 400 before the service runs
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        if (request.method() == PaymentMethod.QRIS) {
            // QRIS follows TX1 -> external call -> TX2; must not run inside PaymentService's @Transactional
            return qrisPayments.create(orderId, request.amount(), request.referenceNote(), idempotencyKey, jwt.getSubject());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payments.createPayment(orderId, request, idempotencyKey, jwt.getSubject()));
    }
}
