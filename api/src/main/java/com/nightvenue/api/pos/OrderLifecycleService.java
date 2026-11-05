package com.nightvenue.api.pos;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ManagerApprovalRequired;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.inventory.InventoryRestoreService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderLifecycleService {
    private static final String SCOPE_VOID = "ORDER_VOID";
    private final OrderRepository orders;
    private final OrderLifecycleRepository lifecycle;
    private final TableRepository tables;
    private final VenueAccessService venues;
    private final IdempotencyRepository idempotency;
    private final InventoryRestoreService inventory;
    private final OrderAuditWriter audit;
    private final BusinessDateService businessDates;
    private final Clock clock;
    private final ObjectMapper mapper;

    public OrderLifecycleService(OrderRepository orders, OrderLifecycleRepository lifecycle, TableRepository tables,
                                 VenueAccessService venues, IdempotencyRepository idempotency,
                                 InventoryRestoreService inventory, OrderAuditWriter audit,
                                 BusinessDateService businessDates, Clock clock, ObjectMapper mapper) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.tables = tables;
        this.venues = venues;
        this.idempotency = idempotency;
        this.inventory = inventory;
        this.audit = audit;
        this.businessDates = businessDates;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public VoidedOrderWithItems voidOrder(long orderId, OrderVoidRequest request, UUID key,
                                           String subject, String role) {
        long actorId = Long.parseLong(subject);
        OrderRepository.Order order = orders.findById(orderId).orElseThrow(ResourceNotFound::new);
        requireVenue(order.venueId());
        if ("CASHIER".equals(role) && "WASTED".equals(request.disposition())) throw new ManagerApprovalRequired();

        String disposition = request.effectiveDisposition();
        String hash = sha256Hex(orderId + "|" + request.reason() + "|" + disposition);
        Instant now = clock.instant();
        if (!idempotency.reserve(key, SCOPE_VOID, actorId, hash, now)) {
            IdempotencyRepository.IdempotencyRow row = idempotency.find(key, SCOPE_VOID, actorId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency row disappeared between reserve and find"));
            if (!hash.equals(row.requestHash())) throw new BusinessRuleViolation("Idempotency-Key reused with a different payload");
            if (row.responseStatus() == null || row.responseBody() == null || row.responseStatus() != 200) {
                throw new IllegalStateException("Idempotency record has no completed response");
            }
            try { return mapper.readValue(row.responseBody(), VoidedOrderWithItems.class); }
            catch (Exception e) { throw new IllegalStateException("Failed to deserialize idempotency snapshot", e); }
        }

        OrderRepository.Order locked = orders.findOrderForUpdate(orderId).orElseThrow(ResourceNotFound::new);
        if (!"OPEN".equals(locked.status())) throw new Conflict("Order does not accept void: status is " + locked.status());
        List<OrderRepository.OrderItem> items = orders.findOrderItems(orderId);
        long threshold = lifecycle.voidApprovalThreshold(locked.venueId());
        boolean aboveThreshold = locked.subtotal() > threshold;
        if ("CASHIER".equals(role) && (aboveThreshold || items.stream().anyMatch(i -> "SERVED".equals(i.status())))) {
            throw new ManagerApprovalRequired();
        }

        if (orders.voidIfOpen(orderId, now) != 1) throw new IllegalStateException("Expected to transition one OPEN order");
        UUID batchId = UUID.randomUUID();
        Map<Long, String> dispositions = new LinkedHashMap<>();
        Map<Long, Long> voidLogIds = new LinkedHashMap<>();
        List<Long> restoreIds = new java.util.ArrayList<>();
        for (OrderRepository.OrderItem item : items) {
            String effective = "SERVED".equals(item.status()) ? "WASTED" : disposition;
            dispositions.put(item.id(), effective);
            Long approvedBy = aboveThreshold && !"CASHIER".equals(role) ? actorId : null;
            long voidLogId = lifecycle.insertVoidLog(locked.venueId(), orderId, item.id(), batchId, actorId,
                    approvedBy, request.reason(), effective, item.lineTotal(), now);
            voidLogIds.put(item.id(), voidLogId);
            if ("ORDERED".equals(item.status()) && "RESTORED".equals(effective)) restoreIds.add(item.id());
        }
        if (lifecycle.transitionItemsToVoided(orderId, now) != items.size()) {
            throw new IllegalStateException("Order item transition count did not match expected count");
        }

        if (!restoreIds.isEmpty()) {
            OrderRepository.VenueContext context = orders.findVenueContext(locked.venueId())
                    .orElseThrow(() -> new IllegalStateException("Venue context not found"));
            LocalDate businessDate = businessDates.businessDate(now, ZoneId.of(context.timezone()), context.businessDayCutoffHour());
            inventory.restore(restoreIds, voidLogIds, actorId, businessDate, now);
        }
        audit.voidOrder(locked.venueId(), actorId, orderId, request.reason(), disposition, batchId, items.size(), now);
        if (locked.tableId() != null && tables.releaseIfOccupied(locked.tableId()) != 1) {
            throw new IllegalStateException("Expected to release one OCCUPIED table");
        }
        OrderRepository.Order updated = orders.findById(orderId).orElseThrow();
        VoidedOrderWithItems response = VoidedOrderWithItems.of(updated, orders.findOrderItems(orderId), dispositions);
        try { idempotency.complete(key, SCOPE_VOID, actorId, 200, mapper.writeValueAsString(response)); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize idempotency snapshot", e); }
        return response;
    }

    @Transactional
    public OrderWithItems serve(long orderId, long itemId, String subject) {
        long actorId = Long.parseLong(subject);
        OrderRepository.Order order = orders.findById(orderId).orElseThrow(ResourceNotFound::new);
        requireVenue(order.venueId());
        OrderRepository.Order locked = orders.findOrderForUpdate(orderId).orElseThrow(ResourceNotFound::new);
        OrderRepository.OrderItem item = orders.findOrderItems(orderId).stream()
                .filter(row -> row.id() == itemId).findFirst().orElseThrow(ResourceNotFound::new);
        if (!List.of("OPEN", "PARTIALLY_PAID", "PAID").contains(locked.status())) {
            throw new Conflict("Order does not accept serve: status is " + locked.status());
        }
        if (!"ORDERED".equals(item.status())) throw new Conflict("Order item does not accept serve: status is " + item.status());
        Instant now = clock.instant();
        if (orders.serveIfOrdered(orderId, itemId, now) != 1) throw new IllegalStateException("Expected to serve one ORDERED item");
        audit.serve(locked.venueId(), actorId, itemId, now);
        return OrderWithItems.of(orders.findById(orderId).orElseThrow(), orders.findOrderItems(orderId));
    }

    private void requireVenue(long venueId) {
        try { venues.requireAccess(venueId); }
        catch (VenueNotFound e) { throw new ResourceNotFound(); }
    }

    private static String sha256Hex(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 not available", e); }
    }
}
