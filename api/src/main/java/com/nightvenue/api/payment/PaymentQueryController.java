package com.nightvenue.api.payment;

import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentQueryController {
    private final PaymentService payments;

    public PaymentQueryController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public PaymentView getPayment(@PathVariable @Positive long paymentId) {
        return payments.getPayment(paymentId);
    }
}
