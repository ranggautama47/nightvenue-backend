package com.nightvenue.api.payment.reconciliation;

import com.nightvenue.api.payment.ChargeState;
import com.nightvenue.api.payment.Payment;
import com.nightvenue.api.payment.PaymentRepository;
import com.nightvenue.api.payment.PaymentStatus;
import com.nightvenue.api.payment.XenditWebhookService;
import com.nightvenue.api.payment.xendit.XenditClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves stale Xendit QRIS payments by asking the Payment Requests Status API
 * for the authoritative outcome.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Candidate ids are read without a transaction. The Status API call happens
 *       outside any transaction and without holding a row lock.</li>
 *   <li>Time only makes a row eligible; it never decides the outcome.</li>
 *   <li>The provider outcome is written through the same M4-A settlement method
 *       ({@link XenditWebhookService#apply}) so locking, correlation revalidation,
 *       CAS, event row and audit stay in one transaction and are not duplicated.</li>
 *   <li>SENDING/NOT_SENT rows and rows without a provider request id cannot be
 *       looked up (there is no verified lookup by reference) and are never mutated;
 *       they are reported once per cycle for manual review.</li>
 * </ul>
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentRepository payments;
    private final XenditClient client;
    private final XenditWebhookService settlement;
    private final PaymentReconciliationProperties properties;
    private final Clock clock;

    public PaymentReconciliationService(PaymentRepository payments, XenditClient client,
                                        XenditWebhookService settlement,
                                        PaymentReconciliationProperties properties, Clock clock) {
        this.payments = payments;
        this.client = client;
        this.settlement = settlement;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one reconciliation cycle and returns the number of candidates inspected.
     * Safe to call directly (tests) or from the scheduler.
     */
    public int reconcile() {
        Instant cutoff = clock.instant().minus(properties.getMinAge());
        List<Long> candidates = payments.findXenditReconciliationCandidates(cutoff, properties.getBatchSize());
        warnUnqueryablePending();

        List<Long> canceled = new ArrayList<>();
        int inspected = 0;
        for (Long paymentId : candidates) {
            if (inspected >= properties.getMaxPerCycle()) break;
            inspected++;
            try {
                if (reconcileOne(paymentId)) canceled.add(paymentId);
            } catch (RuntimeException e) {
                // One bad row must never abort the batch. No payload, body or secret is logged.
                log.warn("xendit reconcile row failed payment_id={} error={}",
                        paymentId, e.getClass().getSimpleName());
            }
        }
        if (!canceled.isEmpty()) {
            log.warn("xendit reconcile provider CANCELED not applied (PENDING->CANCELLED is blocked "
                    + "by PaymentStateMachine) count={} payment_ids={}", canceled.size(), canceled);
        }
        return inspected;
    }

    private void warnUnqueryablePending() {
        List<Long> unqueryable = payments.findXenditUnqueryablePending();
        if (!unqueryable.isEmpty()) {
            log.warn("xendit reconcile manual review required: PENDING rows without a queryable "
                    + "payment_request_id count={} payment_ids={}", unqueryable.size(), unqueryable);
        }
    }

    /** @return true when the row is a provider CANCELED no-op reported once per cycle. */
    private boolean reconcileOne(long paymentId) {
        Payment payment = payments.findById(paymentId).orElse(null);
        if (payment == null) return false;
        // Re-check the candidate rule on the lock-free snapshot; another writer may have moved it.
        if (payment.status() != PaymentStatus.PENDING
                || (payment.chargeState() != ChargeState.ACCEPTED && payment.chargeState() != ChargeState.AMBIGUOUS)
                || payment.providerRequestId() == null) {
            return false;
        }

        // Status API call: OUTSIDE any transaction and without holding a row lock.
        XenditClient.VerificationResult verified = client.getPaymentRequest(payment.providerRequestId());
        if (verified.kind() != XenditClient.VerificationKind.VERIFIED) {
            log.info("xendit reconcile no-write payment_id={} result={}", paymentId, verified.kind());
            return false;
        }

        switch (verified.status()) {
            case "SUCCEEDED", "FAILED", "EXPIRED" -> settlement.apply(paymentId, eventName(verified.status()),
                    payload(verified), verified);
            case "CANCELED" -> {
                return true;
            }
            default -> {
                // REQUIRES_ACTION / AUTHORIZED / ACCEPTING_PAYMENTS / any unknown status: no change.
                log.info("xendit reconcile no-write payment_id={} result=VERIFIED", paymentId);
            }
        }
        return false;
    }

    /** Event key template: reconcile:&lt;providerStatus&gt;:&lt;payment_request_id&gt;:&lt;payment_id&gt; (<=160 chars). */
    private static String eventName(String providerStatus) {
        return "reconcile:" + providerStatus;
    }

    private static Map<String, Object> payload(XenditClient.VerificationResult verified) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request_id", verified.requestId());
        payload.put("reference_id", verified.referenceId());
        payload.put("request_amount", verified.amount());
        payload.put("currency", verified.currency());
        payload.put("country", verified.country());
        payload.put("channel_code", verified.channelCode());
        payload.put("type", verified.type());
        payload.put("payment_id", verified.paymentId());
        payload.put("status", verified.status());
        return payload;
    }
}
