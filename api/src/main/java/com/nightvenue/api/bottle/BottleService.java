package com.nightvenue.api.bottle;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.pos.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class BottleService {

    public static final String SCOPE_DEPOSIT = "BOTTLE_DEPOSIT";
    private static final String CONFLICT_VAULT_EXISTS = "VAULT_ALREADY_EXISTS";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orderRepository;
    private final BottleRepository bottleRepository;
    private final BottleAuditWriter auditWriter;
    private final IdempotencyRepository idempotencyRepo;
    private final VenueAccessService venueAccess;
    private final BusinessDateService businessDates;
    private final BottleProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public BottleService(
            OrderRepository orderRepository,
            BottleRepository bottleRepository,
            BottleAuditWriter auditWriter,
            IdempotencyRepository idempotencyRepo,
            VenueAccessService venueAccess,
            BusinessDateService businessDates,
            BottleProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.bottleRepository = bottleRepository;
        this.auditWriter = auditWriter;
        this.idempotencyRepo = idempotencyRepo;
        this.venueAccess = venueAccess;
        this.businessDates = businessDates;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BottleResponse deposit(DepositBottleRequest request, UUID idempotencyKey, String jwtSubject) {
        if (properties.getRetentionDays() == null || properties.getRetentionDays() <= 0) {
            throw new IllegalStateException("Bottle retention days not configured or invalid");
        }

        long actorId = Long.parseLong(jwtSubject);
        Instant now = clock.instant();

        String hashInput = request.orderId() + "|" + request.orderItemId();
        String requestHash = sha256Hex(hashInput);

        boolean reserved = idempotencyRepo.reserve(idempotencyKey, SCOPE_DEPOSIT, actorId, requestHash, now);

        if (!reserved) {
            IdempotencyRepository.IdempotencyRow row = idempotencyRepo
                    .find(idempotencyKey, SCOPE_DEPOSIT, actorId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency row disappeared between reserve and find"));

            if (!requestHash.equals(row.requestHash())) {
                throw new BusinessRuleViolation("Idempotency-Key reused with a different payload");
            }
            if (row.responseStatus() == null || row.responseBody() == null) {
                throw new IllegalStateException("Idempotency record has no completed response yet");
            }
            if (row.responseStatus() != 201) {
                throw new IllegalStateException("Idempotency record has an unexpected response status: " + row.responseStatus());
            }

            try {
                return objectMapper.readValue(row.responseBody(), BottleResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize idempotency snapshot", e);
            }
        }

        // Lock order row
        OrderRepository.Order order = orderRepository.findOrderForUpdate(request.orderId())
                .orElseThrow(ResourceNotFound::new);

        // Check venue access for current actor
        venueAccess.requireAccess(order.venueId());

        // Validate order status
        if (!"PAID".equals(order.status())) {
            throw new BusinessRuleViolation("Order is not PAID");
        }

        // Validate customer
        if (order.customerId() == null) {
            throw new BusinessRuleViolation("Walk-in order without customer cannot deposit bottle");
        }

        BottleRepository.CustomerInfo customer = bottleRepository.findCustomer(order.customerId())
                .orElseThrow(() -> new BusinessRuleViolation("Customer not found"));

        if (!customer.ageVerified()) {
            throw new BusinessRuleViolation("Customer age is not verified");
        }

        // Validate order item
        BottleRepository.OrderItemProductInfo itemInfo = bottleRepository.findOrderItemProductInfo(request.orderItemId())
                .orElseThrow(ResourceNotFound::new);

        if (itemInfo.orderId() != order.id()) {
            throw new ResourceNotFound();
        }

        if (!"PRODUCT".equals(itemInfo.itemType())) {
            throw new BusinessRuleViolation("Only PRODUCT items can be deposited");
        }

        if ("VOIDED".equals(itemInfo.status())) {
            throw new BusinessRuleViolation("Voided order item cannot be deposited");
        }

        if (!itemInfo.isBottleKeepable()) {
            throw new BusinessRuleViolation("Product is not bottle keepable");
        }

        if (itemInfo.quantity() != 1) {
            throw new BusinessRuleViolation("Order item quantity must be 1 to deposit bottle");
        }

        if (itemInfo.bottleVolumeMl() == null || itemInfo.bottleVolumeMl() <= 0) {
            throw new BusinessRuleViolation("Product bottle volume is not defined");
        }

        // Pre-check for duplicate vault
        if (bottleRepository.vaultExistsForOrderItem(request.orderItemId())) {
            throw new Conflict(CONFLICT_VAULT_EXISTS);
        }

        // Calculate expiry date
        OrderRepository.VenueContext venue = orderRepository.findVenueContext(order.venueId())
                .orElseThrow(ResourceNotFound::new);
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(venue.timezone()), venue.businessDayCutoffHour());
        LocalDate expiryDate = businessDate.plusDays(properties.getRetentionDays());

        // Generate raw token (16 bytes secure random, unpadded Base64 URL) and SHA-256 hash
        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String qrTokenHash = sha256Hex(rawToken);

        // Insert vault record & initial DEPOSIT transaction
        BottleRepository.VaultRecord vault;
        try {
            vault = bottleRepository.insertVaultWithRetry(
                    order.venueId(),
                    order.customerId(),
                    itemInfo.productId(),
                    request.orderItemId(),
                    itemInfo.bottleVolumeMl(),
                    expiryDate,
                    qrTokenHash,
                    now);

            bottleRepository.insertTransaction(
                    vault.id(),
                    request.orderItemId(),
                    "DEPOSIT",
                    itemInfo.bottleVolumeMl(),
                    itemInfo.bottleVolumeMl(),
                    actorId,
                    false,
                    now);
        } catch (DataIntegrityViolationException ex) {
            if (BottleRepository.isSourceOrderItemUniqueViolation(ex)) {
                throw new Conflict(CONFLICT_VAULT_EXISTS);
            }
            throw ex;
        }

        // Audit log (never log token or token hash)
        auditWriter.bottleDeposited(
                order.venueId(),
                actorId,
                vault.id(),
                order.id(),
                request.orderItemId(),
                order.customerId(),
                itemInfo.productId(),
                vault.labelCode(),
                vault.originalVolumeMl(),
                vault.expiryDate(),
                now);

        // Snapshot for replay: qrToken = null, tokenAlreadyIssued = true
        BottleResponse snapshot = new BottleResponse(
                vault.id(),
                vault.labelCode(),
                vault.productId(),
                vault.venueId(),
                vault.customerId(),
                vault.originalVolumeMl(),
                vault.remainingVolumeMl(),
                vault.status(),
                vault.storedAt(),
                vault.expiryDate(),
                null,
                true);

        try {
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            idempotencyRepo.complete(idempotencyKey, SCOPE_DEPOSIT, actorId, 201, snapshotJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotency snapshot", e);
        }

        // Return initial response with raw token and tokenAlreadyIssued = false
        return new BottleResponse(
                vault.id(),
                vault.labelCode(),
                vault.productId(),
                vault.venueId(),
                vault.customerId(),
                vault.originalVolumeMl(),
                vault.remainingVolumeMl(),
                vault.status(),
                vault.storedAt(),
                vault.expiryDate(),
                rawToken,
                false);
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

