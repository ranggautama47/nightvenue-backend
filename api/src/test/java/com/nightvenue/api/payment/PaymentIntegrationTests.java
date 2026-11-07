package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.shift.ShiftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PaymentIntegrationTests {
    private static final String API = "/api/v1/orders";
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jakarta");
    private static final int CUTOFF = 6;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentRepository payments;
    @Autowired ShiftRepository shiftRepository;
    @Autowired BusinessDateService businessDates;

    private String fixture;
    private long venueId, venueBId, tableId, tableWithMinimumId, cashierId, otherCashierId, managerId;
    private long productId, ingredientId, inventoryId;
    private int orderCounter;
    private final List<UUID> keys = new ArrayList<>();

    @BeforeEach
    void setup() {
        fixture = "M2-" + UUID.randomUUID().toString().substring(0, 8);
        cashierId = createUser("CASHIER", "+1");
        otherCashierId = createUser("CASHIER", "+2");
        managerId = createUser("MANAGER", "+3");
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, fixture, fixture);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", managerId, venueId);
        venueBId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, fixture + "-B", fixture + "-B");
        tableId = jdbc.queryForObject("""
                INSERT INTO venue_tables(venue_id,code,capacity,status,is_active,version)
                VALUES (?, ?, 4, 'AVAILABLE', true, 0) RETURNING id
                """, Long.class, venueId, fixture);
        tableWithMinimumId = jdbc.queryForObject("""
                INSERT INTO venue_tables(venue_id,code,capacity,minimum_spend,status,is_active,version)
                VALUES (?, ?, 4, 15000, 'AVAILABLE', true, 0) RETURNING id
                """, Long.class, venueId, fixture + "-M");
        ingredientId = jdbc.queryForObject("INSERT INTO ingredients(sku,name,unit,is_active) VALUES (?,?, 'ML',true) RETURNING id",
                Long.class, fixture, fixture);
        productId = jdbc.queryForObject("""
                INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,is_active,version,created_at,updated_at)
                VALUES (?, ?, ?, 10000, false, true, 0, now(), now()) RETURNING id
                """, Long.class, venueId, fixture, fixture);
        long location = jdbc.queryForObject("INSERT INTO stock_locations(venue_id,name,type) VALUES (?,?,'BAR') RETURNING id",
                Long.class, venueId, fixture);
        inventoryId = jdbc.queryForObject("""
                INSERT INTO inventory_items(location_id,ingredient_id,quantity,reorder_level,version,updated_at)
                VALUES (?, ?, 100000, 0, 0, now()) RETURNING id
                """, Long.class, location, ingredientId);
        long recipeId = jdbc.queryForObject("INSERT INTO recipes(product_id,version,updated_at) VALUES (?,1,now()) RETURNING id", Long.class, productId);
        jdbc.update("INSERT INTO recipe_items(recipe_id,ingredient_id,quantity) VALUES (?, ?, 5)", recipeId, ingredientId);
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?) OR actor_user_id IN (?, ?, ?)",
                venueId, venueBId, cashierId, otherCashierId, managerId);
        jdbc.update("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE venue_id IN (?, ?))", venueId, venueBId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id IN (?, ?)", venueId, venueBId);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id IN (?, ?))", venueId, venueBId);
        jdbc.update("""
                DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                    SELECT ii.id FROM inventory_items ii JOIN stock_locations sl ON sl.id = ii.location_id
                    WHERE sl.venue_id = ?)
                """, venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id IN (?, ?)", venueId, venueBId);
        jdbc.update("DELETE FROM recipe_items WHERE recipe_id IN (SELECT id FROM recipes WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM recipes WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM inventory_items WHERE location_id IN (SELECT id FROM stock_locations WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM ingredients WHERE id = ?", ingredientId);
        jdbc.update("DELETE FROM user_venues WHERE user_id IN (?, ?, ?)", cashierId, otherCashierId, managerId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", cashierId, otherCashierId, managerId);
        jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, venueBId);
    }

    // ---------- CASH ----------

    @Test
    void cashFullClosesOrderReleasesTableAndAudits() throws Exception {
        long orderId = createOrder(venueId, tableId, 10000, 0, 10000, 0, "OPEN");
        jdbc.update("UPDATE venue_tables SET status='OCCUPIED' WHERE id=?", tableId);
        long shiftId = seedShift(venueId, cashierId, businessDate());
        UUID key = key();
        LocalDate businessDate = businessDate();
        MvcResult result = mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.status").value("PAID"))
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andReturn();
        var doc = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(shiftId, doc.path("payment").path("shiftId").asLong());
        assertEquals(10000, doc.path("order").path("paidTotal").asLong());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM orders WHERE id=?", Timestamp.class, orderId));
        assertEquals(10000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM venue_tables WHERE id=?", String.class, tableId));
        var payment = doc.path("payment");
        assertEquals(businessDate.toString(), payment.path("businessDate").asText());
        assertFalse(payment.hasNonNull("provider") || payment.hasNonNull("providerOrderId")
                || payment.hasNonNull("providerTransactionId") || payment.hasNonNull("providerIdempotencyKey")
                || payment.hasNonNull("chargeState") || payment.hasNonNull("expiresAt"));
        assertEquals(0, payment.path("chargeAttempts").asInt());
        assertEquals(cashierId, payment.path("createdBy").asLong());
        assertNotNull(jdbc.queryForObject("SELECT paid_at FROM payments WHERE id=?", java.sql.Timestamp.class, payment.path("id").asLong()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='PAYMENT_CREATED' AND entity_type='PAYMENT'
                """, Integer.class, payment.path("id").asLong()));
    }

    @Test
    void cashPartialSetsPartiallyPaidAndKeepsTableOccupied() throws Exception {
        long orderId = createOrder(venueId, tableId, 10000, 0, 10000, 0, "OPEN");
        jdbc.update("UPDATE venue_tables SET status='OCCUPIED' WHERE id=?", tableId);
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":4000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
        assertEquals("PARTIALLY_PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertNull(jdbc.queryForObject("SELECT closed_at FROM orders WHERE id=?", Timestamp.class, orderId));
        assertEquals(4000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals("OCCUPIED", jdbc.queryForObject("SELECT status FROM venue_tables WHERE id=?", String.class, tableId));
    }

    @Test
    void multiplePartialCashPaymentsUpdatePaidTotalOnSecond() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":4000}"))
                .andExpect(status().isCreated());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":6000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
        assertEquals(10000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void split_2_1_1_closesOnThird() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":5000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":2500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":2500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
        assertEquals(10000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=? AND status='PAID'", Integer.class, orderId));
    }

    @Test
    void cashWithoutOpenShift_409() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(PaymentService.SHIFT_NOT_OPEN));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void cashStaleShift_409_andNoMutation() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        long shiftId = seedShift(venueId, cashierId, businessDate().minusDays(1));
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(PaymentService.SHIFT_NOT_OPEN));
        // OD-2: the payment endpoint must never mutate a shift
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, shiftId));
        assertNull(jdbc.queryForObject("SELECT forced_review_at FROM cashier_shifts WHERE id=?", Timestamp.class, shiftId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
    }

    @Test
    void cashShiftOfOtherCashier_409() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, otherCashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(PaymentService.SHIFT_NOT_OPEN));
    }

    @Test
    void cashShiftInAnotherVenue_409() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueBId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(PaymentService.SHIFT_NOT_OPEN));
    }

    @Test
    void amountAboveRemaining_returns422() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.PAID, 6000, null);
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":5000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Amount exceeds outstanding balance"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(10000, jdbc.queryForObject("SELECT total_due FROM orders WHERE id=?", Long.class, orderId));
    }

    // ---------- Minimum spend (OD-3) ----------

    @Test
    void minimumSpend_exactlyAtMinimum_ok() throws Exception {
        // subtotal 10000, discount 0, snapshot 10000 -> full payment exactly meets the minimum
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 10000, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
    }

    @Test
    void minimumSpend_belowMinimum_exhaustingPayment_422() throws Exception {
        // disposal example: subtotal 10000 -> base 10000 < snapshot 11000; exhausting payment always rejected
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 11000, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(PaymentService.MINIMUM_SPEND_NOT_MET));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
    }

    @Test
    void minimumSpend_partialNotExhausting_ok() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 11000, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":4000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
        assertEquals(4000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
    }

    @Test
    void minimumSpend_discountIncludedInBase() throws Exception {
        // subtotal 10000 + discount 3000 -> base 7000 < snapshot 8000 -> exhausting payment rejected
        long orderId = createOrder(venueId, null, 10000, 3000, 7000, 8000, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":7000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(PaymentService.MINIMUM_SPEND_NOT_MET));
        // raised minimum spend to 7000 -> the same exhausting payment succeeds
        jdbc.update("UPDATE orders SET minimum_spend_snapshot=7000 WHERE id=?", orderId);
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":7000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
    }

    @Test
    void minimumSpend_zeroSnapshotDisabled() throws Exception {
        long orderId = createOrder(venueId, null, 1000, 0, 1000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
    }

    @Test
    void addItemsAfterMinimumSpendRejectionThenComplete_succeeds() throws Exception {
        // order opened through the public API on a table with minimum_spend 15000
        UUID openKey = key();
        MvcResult created = mvc.perform(post(API)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", openKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"tableId\":" + tableWithMinimumId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long orderId = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post(API + "/" + orderId + "/items")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        // base 10000 < snapshot 15000 -> exhausting payment rejected (422), reservation rolled back
        UUID rejectedKey = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", rejectedKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":10000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(PaymentService.MINIMUM_SPEND_NOT_MET));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, rejectedKey));
        // a partial payment to PARTIALLY_PAID is allowed, additional items raise the base above the snapshot
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":3000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
        mvc.perform(post(API + "/" + orderId + "/items")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        // subtotal 20000, outstanding 17000, base 20000 >= 15000 -> completes
        MvcResult finalResult = mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":17000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andReturn();
        assertEquals(20000, mapper.readTree(finalResult.getResponse().getContentAsString()).path("order").path("paidTotal").asLong());
    }

    // ---------- CARD_EDC ----------

    @Test
    void edcFullClosesOrderWithoutAnyShift() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN"); // no seeded shift at all
        MvcResult result = mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.shiftId").isEmpty())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andReturn();
        assertNull(jdbc.queryForObject("SELECT shift_id FROM payments WHERE id=?",
                Long.class, mapper.readTree(result.getResponse().getContentAsString()).path("payment").path("id").asLong()));
    }

    @Test
    void edcPartialSetsPartiallyPaid() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":2500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_PAID"));
    }

    // ---------- Validation ----------

    @Test
    void validationRejectsMissingZeroNegativeAmountAndLongNote() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":-5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1000,\"referenceNote\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void qrisReservedForLaterMilestone_returns422() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        UUID rejectedKey = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", rejectedKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"QRIS_MIDTRANS\",\"amount\":1000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("PAYMENT_METHOD_NOT_SUPPORTED"));
        // the QRIS request must not have consumed its (scoped) idempotency slot
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?",
                Integer.class, rejectedKey));
    }

    @Test
    void referenceNoteNormalization() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1000,\"referenceNote\":\"  EDC123 \\t\"}"))
                .andExpect(status().isCreated());
        long second = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + second + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1000,\"referenceNote\":\"   \"}"))
                .andExpect(status().isCreated());
        assertEquals("EDC123", jdbc.queryForObject("SELECT reference_note FROM payments WHERE order_id=? ORDER BY id LIMIT 1", String.class, orderId));
        assertNull(jdbc.queryForObject("SELECT reference_note FROM payments WHERE order_id=?", String.class, second));
        // literal "-" is stored as "-" and stays different from null
        long third = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        UUID dashKey = key();
        mvc.perform(post(API + "/" + third + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", dashKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1000,\"referenceNote\":\"-\"}"))
                .andExpect(status().isCreated());
        assertEquals("-", jdbc.queryForObject("SELECT reference_note FROM payments WHERE order_id=?", String.class, third));
        // same key with a different normalized value (null vs "-") is a different payload -> 422
        mvc.perform(post(API + "/" + third + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", dashKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":1000}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exactlyHundredCharacterNoteAccepted() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1000,\"referenceNote\":\"" + "x".repeat(100) + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---------- Idempotency ----------

    @Test
    void idempotency_sameKeySameHash_replaysSnapshotAfterOrderPaid() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        UUID key = key();
        String body = "{\"method\":\"CASH\",\"amount\":10000}";
        String first = mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        String replay = mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM payments WHERE order_id=?)
                """, Integer.class, orderId));
    }

    @Test
    void idempotency_sameKeyDifferentPayload_returns422() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        UUID key = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":4000}"))
                .andExpect(status().isCreated());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":6000}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
    }

    @Test
    void idempotency_validationFailure_doesNotConsumeKey() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        UUID key = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":99999}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key));
        // a corrected request can reuse the same key
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
    }

    @Test
    void idempotency_sameKeyParallel_onePaymentEffect() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        UUID key = key();
        String body = "{\"method\":\"CASH\",\"amount\":10000}";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> a = pool.submit(() -> {
            start.await();
            return mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getContentAsString();
        });
        Future<String> b = pool.submit(() -> {
            start.await();
            return mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getContentAsString();
        });
        start.countDown();
        try {
            String responseA = a.get(20, TimeUnit.SECONDS);
            String responseB = b.get(20, TimeUnit.SECONDS);
            long paymentIdA = mapper.readTree(responseA).path("payment").path("id").asLong();
            long paymentIdB = mapper.readTree(responseB).path("payment").path("id").asLong();
            assertNotEquals(0, paymentIdA);
            assertEquals(paymentIdA, paymentIdB, "both callers must receive the stored snapshot");
            assertEquals(responseA, responseB);
        } finally {
            pool.shutdown();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM payments WHERE order_id=?)", Integer.class, orderId));
        assertEquals(10000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
    }

    @Test
    void twoParallelPayments_differentKeys_neverExceedTotal() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String body = "{\"method\":\"CARD_EDC\",\"amount\":6000}";
        Future<Integer> a = pool.submit(() -> {
            start.await();
            return mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> b = pool.submit(() -> {
            start.await();
            return mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        try {
            List<Integer> statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 422), statuses, "exactly one must win; the loser must not oversubscribe");
        } finally {
            pool.shutdown();
        }
        assertEquals(6000, jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=? AND status IN ('PAID','PENDING')", Long.class, orderId));
        assertEquals(6000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals("PARTIALLY_PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    // ---------- Authorization and venue isolation ----------

    @Test
    void paymentRequiresAuthentication() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":1000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerRoleForbidden() throws Exception {
        long customerId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'Cust', 'CUSTOMER', true, 0, now(), now()) RETURNING id
                """, Long.class, fixture.toLowerCase() + "-c@test.local");
        try {
            long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
            mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(customerId, "CUSTOMER")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":1000}"))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", customerId);
        }
    }

    @Test
    void crossVenueOrderConcealed() throws Exception {
        long orderId = createOrder(venueBId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":1000}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyHeaderMissingOrInvalid_returns400() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":1000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD_EDC\",\"amount\":1000}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- BR-20: VOID guard ----------

    @Test
    void void_withPaidPayment_409() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.PAID, 1000, null);
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"paid\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order has active payments"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void void_withPendingPayment_409() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.PENDING, 1000, null);
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"pending\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order has active payments"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void void_withOnlyFailedPayments_allowed() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.FAILED, 1000, null);
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"failed only\"}"))
                .andExpect(status().isOk());
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void void_withOnlyExpiredPayments_allowed() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.EXPIRED, 1000, null);
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"expired only\"}"))
                .andExpect(status().isOk());
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
    }

    // ---------- Settlement ----------

    // OD-9: the payment's business date is derived from the order venue's
    // timezone/cutoff at creation time, never copied from orders.business_date
    // (an order may legitimately be paid on a later business day).
    @Test
    void paymentBusinessDateDerivedAtCreation_notCopiedFromOrder() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        LocalDate yesterday = businessDate().minusDays(1);
        LocalDate today = businessDate();
        jdbc.update("UPDATE orders SET business_date = ? WHERE id = ?", java.sql.Date.valueOf(yesterday), orderId);
        seedShift(venueId, cashierId, today);
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("PAID"));
        assertEquals(today.toString(), jdbc.queryForObject(
                "SELECT business_date::text FROM payments WHERE order_id=?", String.class, orderId));
        // the order's business date was not touched either
        assertEquals(yesterday.toString(), jdbc.queryForObject(
                "SELECT business_date::text FROM orders WHERE id=?", String.class, orderId));
    }

    @Test
    void paidTotalExcludesPending() throws Exception {
        long orderId = createOrder(venueId, null, 10000, 0, 10000, 0, "OPEN");
        seedShift(venueId, cashierId, businessDate());
        seedPayment(orderId, PaymentMethod.CARD_EDC, PaymentStatus.PENDING, 4000, null);
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":3000}"))
                .andExpect(status().isCreated());
        assertEquals(3000, jdbc.queryForObject("SELECT paid_total FROM orders WHERE id=?", Long.class, orderId));
        assertEquals("PARTIALLY_PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE order_id=? AND method='CARD_EDC'", String.class, orderId));
    }

    // ---------- helpers ----------

    private LocalDate businessDate() {
        return businessDates.currentBusinessDate(VENUE_ZONE, CUTOFF);
    }

    private long createUser(String role, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, (fixture.toLowerCase() + suffix + "@test.local"), fixture + suffix, role);
    }

    private long createOrder(long venue, Long tableIdOrNull, long subtotal, long discount,
                             long totalDue, long minimumSpend, String status) {
        orderCounter++;
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,table_id,cashier_id,business_date,status,subtotal,
                                    discount_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?::bigint, ?, ?::date, ?, ?, ?, ?, 0, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venue, fixture + "-O" + orderCounter, tableIdOrNull, cashierId,
                java.sql.Date.valueOf(businessDate()), status, subtotal, discount, totalDue, minimumSpend,
                now, now, now);
    }

    private long seedShift(long venue, long cashier, LocalDate businessDate) {
        return shiftRepository.insertOpenShift(venue, cashier, businessDate, 0L, Instant.now()).id();
    }

    private void seedPayment(long orderId, PaymentMethod method, PaymentStatus status, long amount, Long shiftId) {
        Instant now = Instant.now();
        payments.insert(orderId, venueId, businessDate(), shiftId, method, amount, status,
                null, null, null, null, null, null, 0, null, null,
                status == PaymentStatus.PAID ? now : null, cashierId, now);
    }

    private UUID key() {
        UUID id = UUID.randomUUID();
        keys.add(id);
        return id;
    }

    private String bearer(long id, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
