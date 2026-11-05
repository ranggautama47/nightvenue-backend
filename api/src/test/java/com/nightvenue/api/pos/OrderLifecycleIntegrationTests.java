package com.nightvenue.api.pos;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.venue.TableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OrderLifecycleIntegrationTests {
    private static final String ORDERS = "/api/v1/orders";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired OrderLifecycleService lifecycle;
    @MockitoSpyBean TableRepository tables;
    private String fixture;
    private long venueId, tableId, inventoryId, inventory2Id, ingredientId, ingredient2Id, productId, recipeId, cashierId, managerId;
    private final List<UUID> keys = new ArrayList<>();
    private final List<Long> extraVenueIds = new ArrayList<>();

    @BeforeEach void setup() {
        fixture = "LIFE-" + UUID.randomUUID().toString().substring(0, 8);
        cashierId = demoUser("cashier");
        managerId = demoUser("manager");
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at,void_manager_approval_threshold)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now(), 500000) RETURNING id
                """, Long.class, fixture, fixture);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", managerId, venueId);
        tableId = jdbc.queryForObject("""
                INSERT INTO venue_tables(venue_id,code,capacity,status,is_active,version)
                VALUES (?, ?, 4, 'AVAILABLE', true, 0) RETURNING id
                """, Long.class, venueId, fixture);
        long location = jdbc.queryForObject("INSERT INTO stock_locations(venue_id,name,type) VALUES (?,?,'BAR') RETURNING id",
                Long.class, venueId, fixture);
        ingredientId = jdbc.queryForObject("INSERT INTO ingredients(sku,name,unit,is_active) VALUES (?,?, 'ML',true) RETURNING id",
                Long.class, fixture, fixture);
        inventoryId = jdbc.queryForObject("""
                INSERT INTO inventory_items(location_id,ingredient_id,quantity,reorder_level,version,updated_at)
                VALUES (?, ?, 1000, 0, 0, now()) RETURNING id
                """, Long.class, location, ingredientId);
        productId = jdbc.queryForObject("""
                INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,is_active,version,created_at,updated_at)
                VALUES (?, ?, ?, 100000, false, true, 0, now(), now()) RETURNING id
                """, Long.class, venueId, fixture, fixture);
        ingredient2Id = jdbc.queryForObject("INSERT INTO ingredients(sku,name,unit,is_active) VALUES (?,?, 'GRAM',true) RETURNING id",
                Long.class, fixture + "-2", fixture + "-2");
        inventory2Id = jdbc.queryForObject("""
                INSERT INTO inventory_items(location_id,ingredient_id,quantity,reorder_level,version,updated_at)
                VALUES ((SELECT id FROM stock_locations WHERE venue_id=?), ?, 500, 0, 0, now()) RETURNING id
                """, Long.class, venueId, ingredient2Id);
        recipeId = jdbc.queryForObject("INSERT INTO recipes(product_id,version,updated_at) VALUES (?,1,now()) RETURNING id", Long.class, productId);
        jdbc.update("INSERT INTO recipe_items(recipe_id,ingredient_id,quantity) VALUES (?, ?, 30), (?, ?, 10)", recipeId, ingredientId, recipeId, ingredient2Id);
    }

    @AfterEach void cleanup() {
        if (fixture == null) return;
        for (long other : extraVenueIds) {
            jdbc.update("DELETE FROM audit_logs WHERE venue_id = ?", other);
            jdbc.update("DELETE FROM void_logs WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", other);
            jdbc.update("""
                    DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                        SELECT ii.id FROM inventory_items ii JOIN stock_locations sl ON sl.id=ii.location_id
                        WHERE sl.venue_id=?)
                    """, other);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", other);
            jdbc.update("DELETE FROM orders WHERE venue_id = ?", other);
            jdbc.update("""
                    DELETE FROM recipe_items WHERE recipe_id IN (
                        SELECT r.id FROM recipes r JOIN products p ON p.id=r.product_id WHERE p.venue_id=?)
                    """, other);
            jdbc.update("DELETE FROM recipes WHERE product_id IN (SELECT id FROM products WHERE venue_id=?)", other);
            jdbc.update("DELETE FROM products WHERE venue_id = ?", other);
            jdbc.update("DELETE FROM inventory_items WHERE location_id IN (SELECT id FROM stock_locations WHERE venue_id=?)", other);
            jdbc.update("DELETE FROM stock_locations WHERE venue_id = ?", other);
            jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", other);
            jdbc.update("DELETE FROM user_venues WHERE venue_id = ?", other);
            jdbc.update("DELETE FROM venues WHERE id = ?", other);
        }
        jdbc.update("DELETE FROM audit_logs WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM void_logs WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM inventory_transactions WHERE inventory_item_id IN (?, ?)", inventoryId, inventory2Id);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id = ?", venueId);
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM recipe_items WHERE recipe_id IN (SELECT id FROM recipes WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM recipes WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM inventory_items WHERE id = ?", inventoryId);
        jdbc.update("DELETE FROM inventory_items WHERE id = ?", inventory2Id);
        jdbc.update("DELETE FROM ingredients WHERE id = ?", ingredientId);
        jdbc.update("DELETE FROM ingredients WHERE id = ?", ingredient2Id);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM user_venues WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test void restoredVoidAndServePreserveLedgerAndSnapshots() throws Exception {
        BigDecimal initial = stock(), initial2 = stock2();
        long orderId = createOrder();
        long itemId = addItem(orderId);
        UUID key = UUID.randomUUID(); keys.add(key);
        String response = mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  exact reason  \",\"disposition\":\"RESTORED\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId));
        assertEquals(0, initial.compareTo(stock()));
        assertEquals(0, initial2.compareTo(stock2()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE type='VOID_RETURN' AND reference_type='VOID_LOG'", Integer.class));
        assertTrue(mapper.readTree(response).path("items").get(0).has("disposition"));
        assertEquals("  exact reason  ", jdbc.queryForObject("SELECT reason FROM void_logs WHERE order_item_id=?", String.class, itemId));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM venue_tables WHERE id=?", String.class, tableId));
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM orders WHERE id=?", java.sql.Timestamp.class, orderId));
        List<java.util.Map<String, Object>> deductions = jdbc.queryForList("""
                SELECT t.inventory_item_id, t.delta, t.reference_id AS order_item_id
                FROM inventory_transactions t
                WHERE t.type='SALE_DEDUCT' AND t.reference_type='ORDER_ITEM'
                  AND t.reference_id IN (SELECT id FROM order_items WHERE order_id = ?)
                ORDER BY t.id
                """, orderId);
        List<java.util.Map<String, Object>> returns = jdbc.queryForList("""
                SELECT t.inventory_item_id, t.delta, v.id AS void_log_id, v.order_item_id
                FROM inventory_transactions t
                JOIN void_logs v ON t.reference_id = v.id
                WHERE t.type='VOID_RETURN' AND t.reference_type='VOID_LOG'
                  AND v.order_id = ?
                ORDER BY t.id
                """, orderId);
        assertEquals(deductions.size(), returns.size(), "one VOID_RETURN per SALE_DEDUCT row");
        List<java.util.Map<String, Object>> unmatched = new ArrayList<>(returns);
        for (java.util.Map<String, Object> deduction : deductions) {
            long inventoryItemId = ((Number) deduction.get("inventory_item_id")).longValue();
            long orderItemId = ((Number) deduction.get("order_item_id")).longValue();
            BigDecimal deductionDelta = (BigDecimal) deduction.get("delta");
            boolean matched = unmatched.removeIf(row ->
                    ((Number) row.get("inventory_item_id")).longValue() == inventoryItemId
                            && ((Number) row.get("order_item_id")).longValue() == orderItemId
                            && ((BigDecimal) row.get("delta")).compareTo(deductionDelta.negate()) == 0);
            assertTrue(matched, "VOID_RETURN must mirror SALE_DEDUCT " + deduction);
        }
        assertTrue(unmatched.isEmpty(), "every VOID_RETURN must correspond to a SALE_DEDUCT row");
        String replay = mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  exact reason  \"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(response, replay);
        assertEquals(deductions.size(), jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_transactions
                WHERE type='VOID_RETURN' AND reference_type='VOID_LOG'
                  AND reference_id IN (SELECT id FROM void_logs WHERE order_id=?)
                """, Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='ORDER_VOID'", Integer.class, orderId));
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"second\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order does not accept void: status is VOIDED"));
    }

    @Test void servedItemIsWastedAndServeIsAudited() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId); BigDecimal before = stock();
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"served\"}"))
                .andExpect(status().isOk());
        assertEquals(0, before.compareTo(stock()));
        assertEquals("WASTED", jdbc.queryForObject("SELECT disposition FROM void_logs WHERE order_item_id=?", String.class, itemId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ORDER_ITEM_SERVE' AND entity_id=?", Integer.class, itemId));
    }

    @Test void requestedWastedDoesNotRestoreStockOrWriteWasteLedger() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId); BigDecimal afterSale = stock();
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"wasted\",\"disposition\":\"WASTED\"}"))
                .andExpect(status().isOk());
        assertEquals(0, afterSale.compareTo(stock()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE type='WASTE' AND reference_id=?", Integer.class, itemId));
        assertEquals("WASTED", jdbc.queryForObject("SELECT disposition FROM void_logs WHERE order_item_id=?", String.class, itemId));
    }

    @Test void cashierAtExactThresholdCanVoid() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        jdbc.update("UPDATE venues SET void_manager_approval_threshold=100000 WHERE id=?", venueId);
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"at threshold\"}"))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT approved_by FROM void_logs WHERE order_item_id=?", Long.class, itemId));
    }

    @Test void mixedItemsAndRecipeChangeRestoreOriginalLedgerRows() throws Exception {
        BigDecimal original1=stock(), original2=stock2();
        long orderId=createOrder(); long first=addItem(orderId), second=addItem(orderId);
        jdbc.update("UPDATE recipe_items SET quantity=999 WHERE recipe_id=? AND ingredient_id=?", recipeId, ingredientId);
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + first + "/serve").header("Authorization",bearer(managerId,"MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization",bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"mixed\"}"))
                .andExpect(status().isOk());
        assertEquals(0, original1.subtract(BigDecimal.valueOf(30)).compareTo(stock()));
        assertEquals(0, original2.subtract(BigDecimal.TEN).compareTo(stock2()));
        assertEquals("WASTED", jdbc.queryForObject("SELECT disposition FROM void_logs WHERE order_item_id=?",String.class,first));
        assertEquals("RESTORED", jdbc.queryForObject("SELECT disposition FROM void_logs WHERE order_item_id=?",String.class,second));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE type='VOID_RETURN'",Integer.class));
    }

    @Test void emptyAndNullTableOrdersCanBeVoided() throws Exception {
        long empty=createOrder();
        mvc.perform(post(ORDERS + "/" + empty + "/void").header("Authorization",bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"empty\"}"))
                .andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?",Integer.class,empty));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='ORDER_VOID'",Integer.class,empty));
        long walkIn=createOrder(null);
        mvc.perform(post(ORDERS + "/" + walkIn + "/void").header("Authorization",bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"walk in\"}"))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT table_id FROM orders WHERE id=?",Long.class,walkIn));
    }

    @Test void cashierWastedPrecedenceDoesNotReserveIdempotency() throws Exception {
        long orderId=createOrder(); addItem(orderId); UUID id=key();
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization",bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"wasted\",\"disposition\":\"WASTED\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?",Integer.class,id));
    }

    @Test void cashierApprovalFailureRollsBackReservationAndManagerCanRetry() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        jdbc.update("UPDATE venues SET void_manager_approval_threshold=1 WHERE id=?", venueId);
        UUID key = UUID.randomUUID(); keys.add(key);
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"threshold\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key));
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"threshold\"}"))
                .andExpect(status().isOk());
    }

    @Test void zeroRowTableReleaseRollsBackWholeVoid() throws Exception {
        long orderId = createOrder(); addItem(orderId); BigDecimal before = stock();
        int ledgerBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE type='SALE_DEDUCT' AND reference_id IN (SELECT id FROM order_items WHERE order_id=?)", Integer.class, orderId);
        jdbc.update("UPDATE venue_tables SET status='AVAILABLE' WHERE id=?", tableId);
        Jwt jwt = Jwt.withTokenValue("test").header("alg","none").subject(Long.toString(managerId))
                .claim("role","MANAGER").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        UUID key = key();
        try {
            assertThrows(IllegalStateException.class, () -> lifecycle.voidOrder(orderId,
                    new OrderVoidRequest("i16", null), key, Long.toString(managerId), "MANAGER"));
        } finally { SecurityContextHolder.clearContext(); }
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='ORDER_VOID'", Integer.class, orderId));
        assertEquals(0, before.compareTo(stock()));
        assertEquals(ledgerBefore, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE type='SALE_DEDUCT' AND reference_id IN (SELECT id FROM order_items WHERE order_id=?)", Integer.class, orderId));
    }

    @Test void concurrentOpenDuringPausedReleaseConflictsThenCanOpenAfterVoid() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        CountDownLatch releaseEntered = new CountDownLatch(1), continueRelease = new CountDownLatch(1);
        Answer<Integer> pause = invocation -> {
            releaseEntered.countDown();
            if (!continueRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
            return (Integer) invocation.callRealMethod();
        };
        doAnswer(pause).when(tables).releaseIfOccupied(anyLong());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        UUID voidKey = key();
        Future<Integer> voidStatus = pool.submit(() -> mvc.perform(post(ORDERS + "/" + orderId + "/void")
                        .header("Authorization", bearer(managerId,"MANAGER")).header("Idempotency-Key",voidKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"race\"}"))
                .andReturn().getResponse().getStatus());
        try {
            assertTrue(releaseEntered.await(10, TimeUnit.SECONDS));
            mvc.perform(post(ORDERS).header("Authorization", bearer(cashierId,"CASHIER")).header("Idempotency-Key",key())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":"+venueId+",\"tableId\":"+tableId+"}"))
                    .andExpect(status().isConflict());
        } finally { continueRelease.countDown(); pool.shutdown(); }
        assertEquals(200, voidStatus.get(15, TimeUnit.SECONDS));
        mvc.perform(post(ORDERS).header("Authorization", bearer(cashierId,"CASHIER")).header("Idempotency-Key",key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":"+venueId+",\"tableId\":"+tableId+"}"))
                .andExpect(status().isCreated());
    }

    @Test void invalidDispositionReturnsBadRequest() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"bad\",\"disposition\":\"XYZ\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test void voidValidatesReasonAndIdempotencyHeader() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no key\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test void voidRejectsNonOpenParentStatuses() throws Exception {
        for (String status : List.of("PARTIALLY_PAID", "PAID", "REFUNDED")) {
            long orderId = createOrder(null); addItem(orderId);
            jdbc.update("UPDATE orders SET status=? WHERE id=?", status, orderId);
            mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                            .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"closed\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Order does not accept void: status is " + status));
        }
    }

    @Test void voidSameKeyDifferentPayloadReturnsUnprocessable() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        UUID key = key();
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"first\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"different\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, orderId));
    }

    @Test void cashierServedItemRequiresManagerApproval() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isOk());
        UUID key = key();
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"served\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("MANAGER_APPROVAL_REQUIRED"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key));
    }

    @Test void managerApprovalRecordedOnlyAboveThreshold() throws Exception {
        jdbc.update("UPDATE venues SET void_manager_approval_threshold=1 WHERE id=?", venueId);
        long aboveOrder = createOrder(null); long aboveItem = addItem(aboveOrder);
        mvc.perform(post(ORDERS + "/" + aboveOrder + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"above\"}"))
                .andExpect(status().isOk());
        assertEquals(managerId, jdbc.queryForObject("SELECT approved_by FROM void_logs WHERE order_item_id=?", Long.class, aboveItem));

        jdbc.update("UPDATE venues SET void_manager_approval_threshold=999999 WHERE id=?", venueId);
        long atOrder = createOrder(null); long atItem = addItem(atOrder);
        mvc.perform(post(ORDERS + "/" + atOrder + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"at\"}"))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT approved_by FROM void_logs WHERE order_item_id=?", Long.class, atItem));
    }

    @Test void lifecycleEndpointsRejectOtherVenueOrders() throws Exception {
        OtherVenue other = createVenueWithCatalog();
        long voidOrder = createOrderIn(other.venueId());
        mvc.perform(post(ORDERS + "/" + voidOrder + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cross venue\"}"))
                .andExpect(status().isNotFound());
        long serveOrder = createOrderIn(other.venueId());
        long itemId = addItem(serveOrder, other.productId());
        mvc.perform(post(ORDERS + "/" + serveOrder + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(managerId,"MANAGER")))
                .andExpect(status().isNotFound());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, voidOrder));
        assertEquals("ORDERED", jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId));
    }

    @Test void missingSaleDeductRollsBackWholeVoid() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        jdbc.update("DELETE FROM inventory_transactions WHERE type='SALE_DEDUCT' AND reference_type='ORDER_ITEM' AND reference_id=?", itemId);
        Jwt jwt = Jwt.withTokenValue("test").header("alg","none").subject(Long.toString(managerId))
                .claim("role","MANAGER").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        UUID key = key();
        try {
            assertThrows(IllegalStateException.class, () -> lifecycle.voidOrder(orderId,
                    new OrderVoidRequest("missing ledger", null), key, Long.toString(managerId), "MANAGER"));
        } finally { SecurityContextHolder.clearContext(); }
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='ORDER_VOID'", Integer.class, orderId));
        assertEquals("ORDERED", jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId));
    }

    @Test void voidPreservesFinancialSnapshotsAndLedgerChain() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        java.util.Map<String, Object> before = jdbc.queryForMap("""
                SELECT subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                       minimum_spend_snapshot, business_date
                FROM orders WHERE id=?
                """, orderId);
        mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                        .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"snap\"}"))
                .andExpect(status().isOk());
        java.util.Map<String, Object> after = jdbc.queryForMap("""
                SELECT subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                       minimum_spend_snapshot, business_date
                FROM orders WHERE id=?
                """, orderId);
        assertEquals(before, after);
        assertEquals(jdbc.queryForObject("SELECT business_date FROM orders WHERE id=?", java.sql.Date.class, orderId),
                jdbc.queryForObject("""
                        SELECT business_date FROM inventory_transactions
                        WHERE type='VOID_RETURN' AND reference_id IN (SELECT id FROM void_logs WHERE order_id=?)
                        ORDER BY id LIMIT 1
                        """, java.sql.Date.class, orderId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT balance_after, delta,
                           LAG(balance_after) OVER (PARTITION BY inventory_item_id ORDER BY id) AS prior
                    FROM inventory_transactions WHERE inventory_item_id IN (?, ?)
                ) ledger WHERE prior IS NOT NULL AND balance_after <> prior + delta
                """, Integer.class, inventoryId, inventory2Id));
    }

    @Test void serveRejectsAlreadyServedAndUnknownItem() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order item does not accept serve: status is SERVED"));
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + (itemId + 999999) + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isNotFound());
    }

    @Test void serveParentStatusMatrix() throws Exception {
        for (String status : List.of("PARTIALLY_PAID", "PAID")) {
            long orderId = createOrder(null); long itemId = addItem(orderId);
            jdbc.update("UPDATE orders SET status=? WHERE id=?", status, orderId);
            mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                            .header("Authorization", bearer(cashierId,"CASHIER")))
                    .andExpect(status().isOk());
            assertEquals("SERVED", jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId));
        }
        for (String status : List.of("VOIDED", "REFUNDED")) {
            long orderId = createOrder(null); long itemId = addItem(orderId);
            jdbc.update("UPDATE orders SET status=? WHERE id=?", status, orderId);
            mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                            .header("Authorization", bearer(cashierId,"CASHIER")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Order does not accept serve: status is " + status));
        }
    }

    @Test void serveVoidedItemReturnsConflict() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        jdbc.update("UPDATE order_items SET status='VOIDED' WHERE id=?", itemId);
        mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                        .header("Authorization", bearer(cashierId,"CASHIER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order item does not accept serve: status is VOIDED"));
    }

    @Test void differentKeyDoubleVoidOneSucceedsOneConflicts() throws Exception {
        long orderId = createOrder(); addItem(orderId);
        UUID first = key(), second = key();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                            .header("Idempotency-Key",first).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"race-a\"}"))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> b = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                            .header("Idempotency-Key",second).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"race-b\"}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        try {
            List<Integer> statuses = new ArrayList<>(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
        } finally { pool.shutdown(); }
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key IN (?,?)", Integer.class, first, second));
    }

    @Test void doubleServeOfOneItemOneSucceedsOneConflicts() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                            .header("Authorization", bearer(cashierId,"CASHIER")))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> b = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                            .header("Authorization", bearer(cashierId,"CASHIER")))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        try {
            List<Integer> statuses = new ArrayList<>(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
        } finally { pool.shutdown(); }
        assertEquals("SERVED", jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ORDER_ITEM_SERVE' AND entity_id=?", Integer.class, itemId));
    }

    @Test void serveVsVoidNeverLeavesVoidedOrderWithServedItem() throws Exception {
        long orderId = createOrder(); long itemId = addItem(orderId);
        UUID voidKey = key();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> serve = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/items/" + itemId + "/serve")
                            .header("Authorization", bearer(cashierId,"CASHIER")))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> voiding = pool.submit(() -> {
            start.await();
            return mvc.perform(post(ORDERS + "/" + orderId + "/void").header("Authorization", bearer(managerId,"MANAGER"))
                            .header("Idempotency-Key",voidKey).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"race\"}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        try {
            List<Integer> statuses = List.of(serve.get(15, TimeUnit.SECONDS), voiding.get(15, TimeUnit.SECONDS));
            assertTrue(statuses.stream().noneMatch(status -> status == 500), "no server error: " + statuses);
        } finally { pool.shutdown(); }
        String orderStatus = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
        String itemStatus = jdbc.queryForObject("SELECT status FROM order_items WHERE id=?", String.class, itemId);
        assertFalse("VOIDED".equals(orderStatus) && "SERVED".equals(itemStatus),
                "order " + orderStatus + " must not retain a SERVED item");
    }

    private record OtherVenue(long venueId, long productId) {}

    private OtherVenue createVenueWithCatalog() {
        long other = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, fixture + "-B", fixture + "-B");
        long location = jdbc.queryForObject("INSERT INTO stock_locations(venue_id,name,type) VALUES (?,?,'BAR') RETURNING id",
                Long.class, other, fixture + "-B");
        jdbc.update("INSERT INTO inventory_items(location_id,ingredient_id,quantity,reorder_level,version,updated_at) VALUES (?,?,1000,0,0,now())",
                location, ingredientId);
        jdbc.update("INSERT INTO inventory_items(location_id,ingredient_id,quantity,reorder_level,version,updated_at) VALUES (?,?,500,0,0,now())",
                location, ingredient2Id);
        long otherProduct = jdbc.queryForObject("""
                INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,is_active,version,created_at,updated_at)
                VALUES (?, ?, ?, 100000, false, true, 0, now(), now()) RETURNING id
                """, Long.class, other, fixture + "-B", fixture + "-B");
        long otherRecipe = jdbc.queryForObject("INSERT INTO recipes(product_id,version,updated_at) VALUES (?,1,now()) RETURNING id",
                Long.class, otherProduct);
        jdbc.update("INSERT INTO recipe_items(recipe_id,ingredient_id,quantity) VALUES (?, ?, 30), (?, ?, 10)",
                otherRecipe, ingredientId, otherRecipe, ingredient2Id);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, other);
        extraVenueIds.add(other);
        return new OtherVenue(other, otherProduct);
    }

    private long createOrderIn(long venue) throws Exception {
        UUID key = key();
        MvcResult result = mvc.perform(post(ORDERS).header("Authorization",bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":"+venue+",\"tableId\":null}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long createOrder() throws Exception {
        return createOrder(tableId);
    }
    private long createOrder(Long table) throws Exception {
        UUID key = key();
        MvcResult result = mvc.perform(post(ORDERS).header("Authorization",bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":"+venueId+",\"tableId\":"+(table==null?"null":table)+"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }
    private long addItem(long orderId) throws Exception { return addItem(orderId, productId); }
    private long addItem(long orderId, long product) throws Exception {
        java.util.Set<Long> before = orderItemIds(orderId);
        UUID key = key();
        mvc.perform(post(ORDERS+"/"+orderId+"/items").header("Authorization",bearer(cashierId,"CASHIER"))
                        .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":"+product+",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        java.util.Set<Long> after = orderItemIds(orderId);
        after.removeAll(before);
        assertEquals(1, after.size(), "expected exactly one newly added order item");
        return after.iterator().next();
    }
    private java.util.Set<Long> orderItemIds(long orderId) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM order_items WHERE order_id = ?", Long.class, orderId));
    }
    private UUID key() { UUID id=UUID.randomUUID(); keys.add(id); return id; }
    private BigDecimal stock() { return jdbc.queryForObject("SELECT quantity FROM inventory_items WHERE id=?",BigDecimal.class,inventoryId); }
    private BigDecimal stock2() { return jdbc.queryForObject("SELECT quantity FROM inventory_items WHERE id=?",BigDecimal.class,inventory2Id); }
    private long demoUser(String role) { return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,role+"@nightvenue.demo"); }
    private String bearer(long id,String role) {
        Instant now=Instant.now(); JwtClaimsSet claims=JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role",role).build();
        return "Bearer "+jwtEncoder.encode(JwtEncoderParameters.from(org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
