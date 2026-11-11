package com.nightvenue.api.common;

/**
 * 409: an idempotency key in the PAYMENT_CREATE scope exists without a
 * completed snapshot. The caller must retry later (Retry-After header).
 */
public class PaymentInProgress extends RuntimeException {
}
