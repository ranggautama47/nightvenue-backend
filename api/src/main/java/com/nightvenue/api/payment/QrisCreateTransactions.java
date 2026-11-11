package com.nightvenue.api.payment;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.PaymentInProgress;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.payment.xendit.XenditClient;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * TX1 and TX2 for the QRIS create flow. Called only from QrisPaymentCreator
 * (a bean WITHOUT @Transactional) so that no transaction and no row lock is
 * open while the Xendit HTTP call runs. complete() is called only inside TX2
 * so the payment finalization and the idempotency snapshot commit atomically
 * (plain JdbcTemplate joins the ambient Spring transaction).
 */
@Service
public class QrisCreateTransactions {
    private static final String SCOPE_PAYMENT_CREATE = "PAYMENT_CREATE";

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final IdempotencyRepository idempotency;
    private final OrderAuditWriter audit;
    private final ObjectMapper mapper;

    public QrisCreateTransactions(OrderRepository orders, PaymentRepository payments,
                                  VenueAccessService venues, BusinessDateService businessDates,
                                  IdempotencyRepository idempotency, OrderAuditWriter audit,
                                  ObjectMapper mapper) {
        this.orders = orders;
        this.payments = payments;
        this.venues = venues;
        this.businessDates = businessDates;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** idempotency snapshot wrapper: schema version lets future formats migrate safely */
    public record Snapshot(int schemaVersion, QrisPaymentView body) {}

    public record BeginResult(boolean replay, Integer replayStatus, QrisPaymentView replayView,
                              long paymentId, String referenceId) {
        static BeginResult ofNew(long paymentId, String referenceId) {
            return new BeginResult(false, null, null, paymentId, referenceId);
        }

        static BeginResult ofReplay(int status, QrisPaymentView view) {
            return new BeginResult(true, status, view, 0, null);
        }
    }

    /**
     * TX1: authorize, reserve the idempotency key, lock the order, validate the
     * balance, insert the SENDING payment with its unique provider_reference_id.
     * COMMITS before QrisPaymentCreator performs the external HTTP call.
     */
    @Transactional
    public BeginResult begin(long orderId, long amount, String referenceNote, UUID key,
                             String jwtSubject, Instant now) {
        long actorId = Long.parseLong(jwtSubject);
        String requestHash = PaymentService.sha256Hex(
                PaymentService.canonicalString(orderId, PaymentMethod.QRIS.name(), amount,
                        PaymentService.normalizeReferenceNote(referenceNote)));

        OrderRepository.Order order = orders.findById(orderId).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(order.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound(); // cross-venue concealment
        }

        if (!idempotency.reserve(key, SCOPE_PAYMENT_CREATE, actorId, requestHash, now)) {
            IdempotencyRepository.IdempotencyRow row = idempotency
                    .find(key, SCOPE_PAYMENT_CREATE, actorId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency row disappeared between reserve and find"));
            if (!requestHash.equals(row.requestHash())) {
                throw new BusinessRuleViolation("Idempotency-Key reused with a different payload");
            }
            if (row.responseStatus() == null) {
                // in-flight replay: another request is between TX1 and TX2
                throw new PaymentInProgress();
            }
            Snapshot snapshot = mapper.readValue(row.responseBody(), Snapshot.class);
            return BeginResult.ofReplay(row.responseStatus(), snapshot.body());
        }

        OrderRepository.Order locked = orders.findOrderForUpdate(orderId).orElseThrow(ResourceNotFound::new);
        if (!List.of("OPEN", "PARTIALLY_PAID").contains(locked.status())) {
            throw new Conflict("Order does not accept payments: status is " + locked.status());
        }

        long outstanding = locked.totalDue() - payments.sumActiveByOrder(orderId); // SUM(PAID) + SUM(PENDING)
        if (amount > outstanding) {
            throw new BusinessRuleViolation("Amount exceeds outstanding balance");
        }

        OrderRepository.VenueContext context = orders.findVenueContext(locked.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue context not found"));
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(context.timezone()), context.businessDayCutoffHour());

        Payment payment = payments.insert(
                orderId, locked.venueId(), businessDate, null,
                PaymentMethod.QRIS, amount, PaymentStatus.PENDING,
                "XENDIT",
                null, null, null,               // provider_order_id / transaction_id / idempotency_key
                // V9 requires provider_reference_id NOT NULL, but the payment id only exists after
                // the INSERT: use a unique placeholder, then replace it with the final reference
                "nv_pay_pending_" + UUID.randomUUID(), null, null,
                ChargeState.SENDING, now, 1,    // charge_started_at now: V8 ck_payments_charge_started
                null, PaymentService.normalizeReferenceNote(referenceNote), null, actorId, now);
        String referenceId = "nv_pay_" + payment.id(); // <= 24 chars, fits VARCHAR(64), unique
        payments.setProviderReferenceId(payment.id(), referenceId);
        audit.paymentCreated(locked.venueId(), actorId, payment.id(), orderId,
                PaymentMethod.QRIS.name(), amount, null, now);
        return BeginResult.ofNew(payment.id(), referenceId);
    }

    public record FinishResult(int httpStatus, QrisPaymentView view, String errorCodeHeader) {}

    /**
     * TX2: conditional compare-and-set finalization (fencing) and, only on
     * success, the idempotency snapshot in the same transaction. Any exception
     * rolls the whole finalization back and leaves the row SENDING.
     */
    @Transactional
    public FinishResult finish(long paymentId, UUID key, String jwtSubject, XenditClient.CreateResult result) {
        long actorId = Long.parseLong(jwtSubject);
        Payment payment = payments.findForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment row vanished between TX1 and TX2"));

        ChargeState chargeState;
        PaymentStatus status;
        String requestId = result.paymentRequestId();
        String qr = result.qrString();
        switch (result.outcome()) {
            case CONFIRMED -> {
                // application invariant 5.7: ACCEPTED requires id AND QR; otherwise degrade to AMBIGUOUS
                if (requestId == null || qr == null) {
                    chargeState = ChargeState.AMBIGUOUS;
                    status = PaymentStatus.PENDING;
                    qr = null;
                } else {
                    chargeState = ChargeState.ACCEPTED;
                    status = PaymentStatus.PENDING;
                }
            }
            case REJECTED -> {
                chargeState = ChargeState.REJECTED;
                status = PaymentStatus.FAILED;
                requestId = null;
                qr = null;
            }
            case AMBIGUOUS -> {
                chargeState = ChargeState.AMBIGUOUS;
                status = PaymentStatus.PENDING;
                qr = null; // AMBIGUOUS never carries a QR; a valid request id IS kept (4.4)
            }
            default -> throw new IllegalStateException("Unknown outcome " + result.outcome());
        }

        int updated = payments.finalizeCharge(paymentId, chargeState, status, requestId, qr, payment.version());
        if (updated != 1) {
            // lost the race: someone moved the row off SENDING/version. No complete(), rollback.
            throw new Conflict("PAYMENT_STATE_CONFLICT");
        }

        Payment finalPayment = payments.findById(paymentId).orElseThrow();
        QrisPaymentView view = QrisPaymentView.from(finalPayment, qr);
        int httpStatus = switch (chargeState) {
            case ACCEPTED -> 201;
            case AMBIGUOUS -> 202;
            case REJECTED -> 422;
            default -> throw new IllegalStateException("Unexpected final charge state " + chargeState);
        };
        try {
            idempotency.complete(key, SCOPE_PAYMENT_CREATE, actorId, httpStatus,
                    mapper.writeValueAsString(new Snapshot(1, view)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store idempotency snapshot", e);
        }
        return new FinishResult(httpStatus, view,
                chargeState == ChargeState.REJECTED ? "PAYMENT_PROVIDER_REJECTED" : null);
    }
}
