package com.nightvenue.api.payment;

import com.nightvenue.api.payment.xendit.XenditClient;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import com.nightvenue.api.venue.TableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class XenditWebhookService {
    private static final Logger log = LoggerFactory.getLogger(XenditWebhookService.class);
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final PaymentEventRepository events;
    private final OrderAuditWriter audit;
    private final TableRepository tables;
    private final ObjectMapper mapper;
    private final Clock clock;

    public XenditWebhookService(PaymentRepository payments, OrderRepository orders, PaymentEventRepository events,
                                OrderAuditWriter audit, TableRepository tables, ObjectMapper mapper, Clock clock) {
        this.payments=payments; this.orders=orders; this.events=events; this.audit=audit; this.tables=tables; this.mapper=mapper; this.clock=clock;
    }

    public Optional<Payment> findByReference(String reference) { return payments.findXenditByReferenceId(reference); }
    public Optional<Payment> findByRequestId(String requestId) { return payments.findXenditByRequestId(requestId); }

    public void unmatched(String event, Map<String,Object> data) {
        log.warn("xendit webhook unmatched event={} reference_id={} payment_request_id={} payment_id={}", event,
                data.get("reference_id"), data.get("payment_request_id"), data.get("payment_id"));
    }

    public boolean correlationMatches(Payment local, Map<String,Object> data, XenditClient.VerificationResult v) {
        String requestId = string(data.get("payment_request_id"));
        return "XENDIT".equals(local.provider()) && local.providerReferenceId().equals(v.referenceId())
                && local.amount() == v.amount() && "IDR".equals(v.currency()) && "ID".equals(v.country())
                && "QRIS".equals(v.channelCode()) && "PAY".equals(v.type())
                && v.requestId().equals(requestId)
                && (local.providerRequestId() == null || local.providerRequestId().equals(requestId))
                && (data.get("reference_id") == null || local.providerReferenceId().equals(data.get("reference_id")))
                && local.amount() == number(data.get("request_amount"))
                && v.currency().equals(data.get("currency")) && v.country().equals(data.get("country"))
                && v.channelCode().equals(data.get("channel_code")) && v.type().equals(data.get("type"));
    }

    @Transactional
    public void record(Payment payment, String event, String requestId, Map<String,Object> payload, PaymentEventResult result) {
        applyTransaction(payment.id(), event, payload, null, result);
    }

    @Transactional
    public void apply(long paymentId, String event, Map<String,Object> payload, XenditClient.VerificationResult v) {
        applyTransaction(paymentId, event, payload, v, null);
    }

    private void applyTransaction(long paymentId, String event, Map<String,Object> payload,
                                  XenditClient.VerificationResult v, PaymentEventResult forcedResult) {
        Payment snapshot=payments.findById(paymentId).orElseThrow();
        orders.findOrderForUpdate(snapshot.orderId()).orElseThrow();
        Payment p=payments.findForUpdate(paymentId).orElseThrow();
        String requestId=string(payload.get("payment_request_id"));
        String eventKey=event+":"+requestId+":"+String.valueOf(payload.getOrDefault("payment_id",payload.getOrDefault("capture_id","")));
        PaymentEventResult result=forcedResult == null ? PaymentEventResult.IGNORED_STALE : forcedResult;
        boolean correlationValid = v != null && correlationMatches(p, payload, v);
        if (v != null && forcedResult == null && !correlationValid) {
            result = PaymentEventResult.REJECTED;
            audit.anomaly(p.venueId(), "PAYMENT_PROVIDER_MISMATCH", "PAYMENT", p.id(),
                    Map.of("event", event, "payment_request_id", requestId), clock.instant());
            log.warn("xendit webhook mismatch event={} payment_id={} reference_id={} payment_request_id={}",
                    event, p.id(), string(payload.get("reference_id")), requestId);
        }
        if (v != null && forcedResult == null && correlationValid) {
            PaymentStatus next = switch (v.status()) {
                case "SUCCEEDED" -> PaymentStatus.PAID;
                case "FAILED" -> PaymentStatus.FAILED;
                case "EXPIRED" -> PaymentStatus.EXPIRED;
                default -> null;
            };
            if ("SUCCEEDED".equals(v.status()) && p.chargeState() == ChargeState.REJECTED) {
                result = PaymentEventResult.REJECTED;
                audit.anomaly(p.venueId(), "PAYMENT_PROVIDER_STATE_MISMATCH", "PAYMENT", p.id(),
                        Map.of("provider_status", v.status(), "charge_state", p.chargeState().name()), clock.instant());
                log.warn("xendit webhook rejected charge state event={} payment_id={} reference_id={} payment_request_id={}",
                        event, p.id(), p.providerReferenceId(), requestId);
            } else if ("SUCCEEDED".equals(v.status()) &&
                    (p.status() == PaymentStatus.FAILED || p.status() == PaymentStatus.EXPIRED || p.status() == PaymentStatus.CANCELLED)) {
                result = PaymentEventResult.REJECTED;
                audit.anomaly(p.venueId(), "PAYMENT_TERMINAL_SUCCESS_REVIEW", "PAYMENT", p.id(),
                        Map.of("local_status", p.status().name(), "provider_status", v.status()), clock.instant());
                log.warn("xendit terminal success requires manual review payment_id={} reference_id={} payment_request_id={} local_status={}",
                        p.id(), p.providerReferenceId(), requestId, p.status());
            } else if (next != null && !(next == PaymentStatus.PAID && p.status() != PaymentStatus.PENDING)
                    && PaymentStateMachine.canTransition(p.status(), next, true)
                    && payments.applyVerifiedStatus(p.id(), p.status(), next, p.chargeState().name(),
                    v.requestId(), clock.instant()) == 1) {
                result=PaymentEventResult.PROCESSED;
                audit.paymentStatusChanged(p.venueId(), p.id(), p.orderId(), p.status(), next, clock.instant());
                if (next == PaymentStatus.PAID) {
                    audit.paymentSettled(p.venueId(), p.id(), p.orderId(), p.amount(), clock.instant());
                    OrderRepository.Order order=orders.findById(p.orderId()).orElseThrow();
                    long paidTotal=payments.sumByStatus(p.orderId(),PaymentStatus.PAID);
                    orders.applySettlement(order, paidTotal, p.id(), audit, clock.instant());
                    if ("PAID".equals(order.status())) {
                        audit.anomaly(order.venueId(), "PAYMENT_OVERPAID", "PAYMENT", p.id(), Map.of("order_id",p.orderId()), clock.instant());
                    } else if (("OPEN".equals(order.status()) || "PARTIALLY_PAID".equals(order.status()))
                            && paidTotal >= order.totalDue() && order.tableId() != null) {
                        tables.releaseIfOccupied(order.tableId());
                    }
                }
            }
        }
        String sanitized=mapper.writeValueAsString(Map.of("event",event,"payment_request_id",requestId,
                "payment_id",String.valueOf(payload.getOrDefault("payment_id","")),"status",String.valueOf(payload.getOrDefault("status","")),
                "reference_id",String.valueOf(payload.getOrDefault("reference_id","")),
                "capture_id",String.valueOf(payload.getOrDefault("capture_id","")),
                "failure_code",String.valueOf(payload.getOrDefault("failure_code",""))));
        events.insert(p.id(),"XENDIT",eventKey,result,sanitized,clock.instant());
    }

    private static String string(Object o){return o==null?null:String.valueOf(o);}
    private static long number(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? Long.MIN_VALUE : Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException ex) { return Long.MIN_VALUE; }
    }
}
