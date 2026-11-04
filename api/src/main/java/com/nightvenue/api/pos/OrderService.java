package com.nightvenue.api.pos;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.catalog.ProductRepository;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.MoneyMath;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.common.idempotency.IdempotencyRepository;
import com.nightvenue.api.inventory.InventoryDeductService;
import com.nightvenue.api.venue.TableRepository;
import com.nightvenue.api.venue.TableRepository.VenueTable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Validated
public class OrderService {
    private static final String TABLE_CONFLICT = "Table is not available";
    private static final String SCOPE_OPEN = "ORDER_OPEN";
    private static final String SCOPE_ADD_ITEMS = "ORDER_ADD_ITEMS";

    private final OrderRepository orders;
    private final TableRepository tables;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final Clock clock;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final ProductRepository products;
    private final InventoryDeductService inventoryDeductService;

    public OrderService(
            OrderRepository orders,
            TableRepository tables,
            VenueAccessService venues,
            BusinessDateService businessDates,
            Clock clock,
            IdempotencyRepository idempotencyRepo,
            ObjectMapper objectMapper,
            ProductRepository products,
            InventoryDeductService inventoryDeductService) {
        this.orders = orders;
        this.tables = tables;
        this.venues = venues;
        this.businessDates = businessDates;
        this.clock = clock;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.products = products;
        this.inventoryDeductService = inventoryDeductService;
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

        boolean inserted = idempotencyRepo.reserve(idempotencyKey, SCOPE_OPEN, cashierId, requestHash, now);

        if (!inserted) {
            // Replay path — same key already exists for this user+scope
            IdempotencyRepository.IdempotencyRow row = idempotencyRepo
                    .find(idempotencyKey, SCOPE_OPEN, cashierId)
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
            idempotencyRepo.complete(idempotencyKey, SCOPE_OPEN, cashierId, 201, bodyJson);

            return created;
        } catch (DataIntegrityViolationException exception) {
            if (isActiveTableUniqueViolation(exception)) {
                throw new Conflict(TABLE_CONFLICT);
            }
            throw exception;
        }
    }

    @Transactional
    public OrderWithItems addItems(long orderId, AddOrderItemsRequest request, UUID idempotencyKey, String jwtSubject) {
        // Step 0: Single Instant for consistency across timestamps
        Instant now = clock.instant();

        // Step 1: Read order without lock -> 404 if missing -> check venue access BEFORE replay
        OrderRepository.Order order = orders.findById(orderId).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(order.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }

        // Step 2: requestHash = SHA-256 of orderId + "|" + sorted items ("productId:quantity")
        long cashierId = Long.parseLong(jwtSubject);
        String itemsStr = request.items().stream()
                .sorted(Comparator.comparingLong(AddOrderItemsRequest.ItemRow::productId)
                        .thenComparingInt(AddOrderItemsRequest.ItemRow::quantity))
                .map(r -> r.productId() + ":" + r.quantity())
                .collect(Collectors.joining(","));
        String hashInput = orderId + "|" + itemsStr;
        String requestHash = sha256Hex(hashInput);

        // Step 3: Idempotency reservation and replay check
        boolean inserted = idempotencyRepo.reserve(idempotencyKey, SCOPE_ADD_ITEMS, cashierId, requestHash, now);
        if (!inserted) {
            IdempotencyRepository.IdempotencyRow row = idempotencyRepo
                    .find(idempotencyKey, SCOPE_ADD_ITEMS, cashierId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency row disappeared between reserve and find"));
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
                return objectMapper.readValue(row.responseBody(), OrderWithItems.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize idempotency snapshot", e);
            }
        }

        // Step 4: Lock order row and verify status accepts items
        OrderRepository.Order lockedOrder = orders.findOrderForUpdate(orderId)
                .orElseThrow(ResourceNotFound::new);
        if (!"OPEN".equals(lockedOrder.status()) && !"PARTIALLY_PAID".equals(lockedOrder.status())) {
            throw new Conflict("Order does not accept items: status is " + lockedOrder.status());
        }

        // Step 5: Resolve and validate each distinct product and recipe once.
        Map<Long, ProductRepository.Product> resolvedProducts = new LinkedHashMap<>();
        Map<Long, ProductRepository.Recipe> resolvedRecipes = new LinkedHashMap<>();
        for (AddOrderItemsRequest.ItemRow row : request.items()) {
            ProductRepository.Product product = resolvedProducts.computeIfAbsent(row.productId(), id ->
                    products.findProduct(id).orElseThrow(() -> new BusinessRuleViolation("Product does not exist")));
            if (product.venueId() != lockedOrder.venueId()) {
                throw new BusinessRuleViolation("Product does not belong to this venue");
            }
            if (!product.active()) {
                throw new BusinessRuleViolation("Product is not active");
            }
            if (product.bottleKeepable() && row.quantity() != 1) {
                throw new BusinessRuleViolation("Bottle product quantity must be 1");
            }
            ProductRepository.Recipe recipe = resolvedRecipes.computeIfAbsent(product.id(), id ->
                    products.findRecipe(id).orElseThrow(() -> new BusinessRuleViolation("Product has no recipe")));
            if (recipe.items().isEmpty()) {
                throw new BusinessRuleViolation("Product has no recipe items");
            }
            try {
                Math.multiplyExact(product.price(), (long) row.quantity());
            } catch (ArithmeticException e) {
                throw new BusinessRuleViolation("Line total overflow");
            }
        }

        // Insert order_items
        List<OrderRepository.OrderItem> insertedItems = new ArrayList<>();
        for (AddOrderItemsRequest.ItemRow row : request.items()) {
            ProductRepository.Product product = resolvedProducts.get(row.productId());
            long lineTotal = Math.multiplyExact(product.price(), (long) row.quantity());
            OrderRepository.OrderItem insertedItem = orders.insertOrderItem(
                    orderId,
                    product.id(),
                    product.name(),
                    product.price(),
                    row.quantity(),
                    lineTotal,
                    now
            );
            insertedItems.add(insertedItem);
        }

        // Step 6: Form deduction pairs, resolve inventory_items.id at BAR location, sort by inventory_items.id ASC
        long barLocationId = orders.findBarLocationId(lockedOrder.venueId())
                .orElseThrow(() -> new Conflict("No BAR stock location found for venue"));

        record DeductionPair(
                long inventoryItemId,
                long orderItemId,
                long ingredientId,
                String ingredientName,
                BigDecimal need
        ) {}

        List<DeductionPair> pairs = new ArrayList<>();
        for (OrderRepository.OrderItem item : insertedItems) {
            ProductRepository.Recipe recipe = resolvedRecipes.get(item.productId());
            for (ProductRepository.RecipeItem recipeItem : recipe.items()) {
                BigDecimal need = recipeItem.quantity()
                        .multiply(BigDecimal.valueOf(item.quantity()))
                        .setScale(3, RoundingMode.HALF_UP);
                long inventoryItemId = orders.findInventoryItemId(barLocationId, recipeItem.ingredientId())
                        .orElseThrow(() -> new Conflict("Insufficient stock: " + recipeItem.ingredientName()));
                pairs.add(new DeductionPair(
                        inventoryItemId,
                        item.id(),
                        recipeItem.ingredientId(),
                        recipeItem.ingredientName(),
                        need
                ));
            }
        }
        // Sort pairs by inventory_items.id ASC, then order_item_id ASC
        pairs.sort(Comparator.comparingLong(DeductionPair::inventoryItemId)
                .thenComparingLong(DeductionPair::orderItemId));

        // Step 7: Call 4C deduct primitive per pair in globally sorted order
        OrderRepository.VenueContext venueCtx = orders.findVenueContext(lockedOrder.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue context not found"));
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(venueCtx.timezone()), venueCtx.businessDayCutoffHour());

        for (DeductionPair pair : pairs) {
            try {
                inventoryDeductService.deductForReference(
                        lockedOrder.venueId(),
                        barLocationId,
                        List.of(new InventoryDeductService.IngredientQty(pair.ingredientId(), pair.need())),
                        "ORDER_ITEM",
                        pair.orderItemId(),
                        businessDate,
                        cashierId
                );
            } catch (Conflict e) {
                throw new Conflict("Insufficient stock: " + pair.ingredientName());
            }
        }

        // Step 8: Recalculate totals from DB (D5 Option A) and update order
        long subtotal;
        try {
            subtotal = orders.calculateSubtotal(orderId).longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessRuleViolation("Subtotal overflow");
        }
        OrderRepository.VenueRates rates = orders.findVenueRates(lockedOrder.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue rates not found"));
        long base = subtotal - lockedOrder.discountAmount();
        long serviceChargeAmount = MoneyMath.applyBps(base, rates.serviceChargeBps());
        long taxAmount = MoneyMath.applyBps(base, rates.taxRateBps());
        long totalDue;
        try {
            totalDue = Math.addExact(base, Math.addExact(serviceChargeAmount, taxAmount));
        } catch (ArithmeticException e) {
            throw new BusinessRuleViolation("Total due overflow");
        }

        orders.updateOrderTotals(orderId, subtotal, serviceChargeAmount, taxAmount, totalDue, now);

        OrderRepository.Order updatedOrder = orders.findById(orderId).orElseThrow();
        List<OrderRepository.OrderItem> allItems = orders.findOrderItems(orderId);
        OrderWithItems result = OrderWithItems.of(updatedOrder, allItems);

        // Step 9: Complete idempotency record with snapshot and return
        try {
            String bodyJson = objectMapper.writeValueAsString(result);
            idempotencyRepo.complete(idempotencyKey, SCOPE_ADD_ITEMS, cashierId, 201, bodyJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotency snapshot", e);
        }

        return result;
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
