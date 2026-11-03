package com.nightvenue.api.pos;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.venue.TableRepository;
import com.nightvenue.api.venue.TableRepository.VenueTable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
@Validated
public class OrderService {
    private static final String TABLE_CONFLICT = "Table is not available";
    private static final String SCOPE = "ORDER_OPEN";

    private final OrderRepository orders;
    private final TableRepository tables;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final Clock clock;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orders,
            TableRepository tables,
            VenueAccessService venues,
            BusinessDateService businessDates,
            Clock clock,
            IdempotencyRepository idempotencyRepo,
            ObjectMapper objectMapper) {
        this.orders = orders;
        this.tables = tables;
        this.venues = venues;
        this.businessDates = businessDates;
        this.clock = clock;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderRepository.Order open(CreateOrderRequest request, UUID idempotencyKey, String jwtSubject) {
        venues.requireAccess(request.venueId());

        long cashierId = Long.parseLong(jwtSubject);
        Instant now = clock.instant();

        // Compute request hash: SHA-256 of "venueId|tableId|customerId" (null -> "-")
        String hashInput = request.venueId()
                + "|" + (request.tableId() == null ? "-" : request.tableId())
                + "|" + (request.customerId() == null ? "-" : request.customerId());
        String requestHash = sha256Hex(hashInput);

        boolean inserted = idempotencyRepo.reserve(idempotencyKey, SCOPE, cashierId, requestHash, now);

        if (!inserted) {
            // Replay path — same key already exists for this user+scope
            IdempotencyRepository.IdempotencyRow row = idempotencyRepo
                    .find(idempotencyKey, SCOPE, cashierId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency row disappeared between reserve and find"));
            if (!requestHash.equals(row.requestHash())) {
                throw new BusinessRuleViolation(
                        "Idempotency-Key reused with a different payload");
            }
            if (row.responseStatus() == null || row.responseBody() == null) {
                throw new IllegalStateException(
                        "Idempotency record has no completed response yet");
            }
            if (row.responseStatus() != 201) {
                throw new IllegalStateException(
                        "Idempotency record has an unexpected response status");
            }
            try {
                return objectMapper.readValue(row.responseBody(), OrderRepository.Order.class);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to deserialize idempotency snapshot", e);
            }
        }

        // New request — run the existing Phase 5A order-creation flow
        OrderRepository.VenueContext venue = orders.findVenueContext(request.venueId())
                .orElseThrow(ResourceNotFound::new);
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(venue.timezone()), venue.businessDayCutoffHour());

        VenueTable table = null;
        if (request.tableId() != null) {
            table = tables.findByIdForUpdate(request.tableId()).orElse(null);
            if (table == null || !Objects.equals(table.venueId(), request.venueId()) || !table.active()) {
                throw new BusinessRuleViolation("Table does not exist in this venue");
            }
            if (!"AVAILABLE".equals(table.status())) {
                throw new Conflict(TABLE_CONFLICT);
            }
        }

        if (request.customerId() != null && !orders.customerExists(request.customerId())) {
            throw new BusinessRuleViolation("Customer does not exist");
        }

        try {
            OrderRepository.Order created = orders.insert(
                    request.venueId(),
                    request.tableId(),
                    request.customerId(),
                    cashierId,
                    businessDate,
                    table == null ? 0 : table.minimumSpend(),
                    now);

            if (table != null && tables.occupyIfAvailable(table.id()) == 0) {
                throw new Conflict(TABLE_CONFLICT);
            }

            // Persist the response snapshot so replays can return it
            String bodyJson;
            try {
                bodyJson = objectMapper.writeValueAsString(created);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize order snapshot", e);
            }
            idempotencyRepo.complete(idempotencyKey, SCOPE, cashierId, 201, bodyJson);

            return created;
        } catch (DataIntegrityViolationException exception) {
            if (isActiveTableUniqueViolation(exception)) {
                throw new Conflict(TABLE_CONFLICT);
            }
            throw exception;
        }
    }

    public OrderRepository.Order get(long id) {
        OrderRepository.Order order = orders.findById(id).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(order.venueId());
        } catch (VenueNotFound exception) {
            throw new ResourceNotFound();
        }
        return order;
    }

    private static boolean isActiveTableUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("uq_orders_active_table")) {
                return true;
            }
        }
        return false;
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

    public record CreateOrderRequest(
            @NotNull @Positive Long venueId,
            @Positive Long tableId,
            @Positive Long customerId) {
    }
}
