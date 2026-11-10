package com.nightvenue.api.payment;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import com.nightvenue.api.shift.CashierShift;
import com.nightvenue.api.shift.ShiftRepository;
import com.nightvenue.api.venue.TableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {
    private static final String SCOPE_PAYMENT_CREATE = "PAYMENT_CREATE";
    /** the code is sent as the Problem detail because BusinessRuleViolation carries no separate code field */
    public static final String SHIFT_NOT_OPEN = "SHIFT_NOT_OPEN";
    public static final String MINIMUM_SPEND_NOT_MET = "MINIMUM_SPEND_NOT_MET";

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ShiftRepository shifts;
    private final TableRepository tables;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final IdempotencyRepository idempotency;
    private final OrderAuditWriter audit;
    private final Clock clock;
    private final ObjectMapper mapper;

    public PaymentService(OrderRepository orders, PaymentRepository payments, ShiftRepository shifts,
                          TableRepository tables, VenueAccessService venues, BusinessDateService businessDates,
                          IdempotencyRepository idempotency, OrderAuditWriter audit, Clock clock,
                          ObjectMapper mapper) {
        this.orders = orders;
        this.payments = payments;
        this.shifts = shifts;
        this.tables = tables;
        this.venues = venues;
        this.businessDates = businessDates;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public PaymentWithOrder createPayment(long orderId, PaymentRequest request, UUID key, String jwtSubject) {
        long actorId = Long.parseLong(jwtSubject);
        Instant now = clock.instant();

        if (request.method() == PaymentMethod.QRIS_MIDTRANS) {
            throw new BusinessRuleViolation("PAYMENT_METHOD_NOT_SUPPORTED");
        }
        String method = request.method().name();
        String referenceNote = normalizeReferenceNote(request.referenceNote());
        String requestHash = sha256Hex(canonicalString(orderId, method, request.amount(), referenceNote));

        OrderRepository.Order order = orders.findById(orderId).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(order.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }

        if (!idempotency.reserve(key, SCOPE_PAYMENT_CREATE, actorId, requestHash, now)) {
            // Replay path (5A/5C convention): return the stored creation snapshot, 201 body, no order lock, no second effect
            IdempotencyRepository.IdempotencyRow row = idempotency.find(key, SCOPE_PAYMENT_CREATE, actorId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency row disappeared between reserve and find"));
            if (!requestHash.equals(row.requestHash())) {
                throw new BusinessRuleViolation("Idempotency-Key reused with a different payload");
            }
            if (row.responseStatus() == null || row.responseBody() == null) {
                throw new IllegalStateException("Idempotency record has no completed response yet");
            }
            if (row.responseStatus() != 201) {
                throw new IllegalStateException("Idempotency record has an unexpected response status");
            }
            try {
                return mapper.readValue(row.responseBody(), PaymentWithOrder.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize idempotency snapshot", e);
            }
        }

        OrderRepository.Order locked = orders.findOrderForUpdate(orderId).orElseThrow(ResourceNotFound::new);
        if (!List.of("OPEN", "PARTIALLY_PAID").contains(locked.status())) {
            throw new Conflict("Order does not accept payments: status is " + locked.status());
        }

        long sumActive = payments.sumActiveByOrder(orderId); // SUM(PAID) + SUM(PENDING)
        long outstanding = locked.totalDue() - sumActive;
        if (request.amount() > outstanding) {
            throw new BusinessRuleViolation("Amount exceeds outstanding balance");
        }
        long minimumSpend = locked.minimumSpendSnapshot(); // null column maps to 0, meaning no requirement
        if (minimumSpend > 0) {
            long base = locked.subtotal() - locked.discountAmount();
            if (sumActive + request.amount() >= locked.totalDue() && base < minimumSpend) {
                throw new BusinessRuleViolation(MINIMUM_SPEND_NOT_MET);
            }
        }

        OrderRepository.VenueContext context = orders.findVenueContext(locked.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue context not found"));
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(context.timezone()), context.businessDayCutoffHour());

        Long shiftId = null;
        if (request.method() == PaymentMethod.CASH) {
            CashierShift shift = shifts.findOpenShiftForPayment(actorId)
                    .orElseThrow(() -> new Conflict(SHIFT_NOT_OPEN));
            if (shift.venueId() != locked.venueId()
                    || !businessDate.equals(shift.businessDate())) {
                throw new Conflict(SHIFT_NOT_OPEN);
            }
            shiftId = shift.id();
        }

        Payment payment = payments.insert(
                orderId,
                locked.venueId(),
                businessDate,
                shiftId,
                request.method(),
                request.amount(),
                PaymentStatus.PAID,
                null, null, null, null, null, null,
                0,
                null,
                referenceNote,
                now,
                actorId,
                now);

        long paidTotal = payments.sumByStatus(orderId, PaymentStatus.PAID); // excludes PENDING
        boolean fullyPaid = paidTotal >= locked.totalDue();
        int updated = orders.updatePaidTotalAndStatus(
                orderId, paidTotal, fullyPaid ? "PAID" : "PARTIALLY_PAID", fullyPaid ? now : null, now);
        if (updated != 1) {
            throw new IllegalStateException("Expected to update exactly one open order");
        }

        if (fullyPaid && locked.tableId() != null) {
            // Addendum A1: the table was already not OCCUPIED is not an error; never fail a payment on 0 rows
            tables.releaseIfOccupied(locked.tableId());
        }

        audit.paymentCreated(locked.venueId(), actorId, payment.id(), orderId, method, request.amount(), shiftId, now);

        OrderRepository.Order updatedOrder = orders.findById(orderId).orElseThrow();
        PaymentWithOrder response = new PaymentWithOrder(payment, updatedOrder);
        try {
            idempotency.complete(key, SCOPE_PAYMENT_CREATE, actorId, 201, mapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotency snapshot", e);
        }
        return response;
    }

    /** Read-only payment lookup for GET /api/v1/payments/{paymentId}; no locks, no writes. */
    public PaymentView getPayment(long paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(payment.venueId());
        } catch (VenueNotFound e) {
            // Cross-venue concealment: same 404 an unknown id returns
            throw new ResourceNotFound();
        }
        return PaymentView.from(payment);
    }

    /** A2: exposed for unit tests that assert the exact canonical format "v1|42|CASH|10000|" */
    static String canonicalString(long orderId, String method, long amount, String normalizedReferenceNote) {
        return "v1|" + orderId + "|" + method + "|" + amount + "|" + (normalizedReferenceNote == null ? "" : normalizedReferenceNote);
    }

    static String normalizeReferenceNote(String raw) {
        if (raw == null) return null;
        String normalized = raw.strip(); // trim; null, empty, whitespace-only all become null
        return normalized.isEmpty() ? null : normalized;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
