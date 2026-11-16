package com.nightvenue.api.bottle;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfig.class, BottleIntegrationTests.TestClockConfig.class})
public class BottleIntegrationTests {

    private static final String BOTTLE_URL = "/api/v1/bottles";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestClock testClock;

    @Autowired
    private BottleProperties bottleProperties;

    private String fixture;
    private long venueId;
    private long otherVenueId;
    private long cashierId;
    private long managerId;
    private long adminId;
    private long customerUserId;
    private long customerId;
    private long verifiedCustomerId;
    private long unverifiedCustomerId;
    private long productId;
    private long nonKeepableProductId;
    private long orderId;
    private long orderItemId;

    private final Set<UUID> recordedKeys = Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void setUp() {
        fixture = "bt" + UUID.randomUUID().toString().substring(0, 8);
        testClock.set(Instant.parse("2026-03-01T12:00:00Z"));

        Timestamp now = Timestamp.from(testClock.instant());

        // Venues
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture + "-v1", fixture + "-v1", now, now);

        otherVenueId = jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture + "-v2", fixture + "-v2", now, now);

        // Users
        cashierId = createTestUser("CASHIER", fixture + "-cashier@nv.test");
        managerId = createTestUser("MANAGER", fixture + "-manager@nv.test");
        adminId = createTestUser("ADMIN", fixture + "-admin@nv.test");
        customerUserId = createTestUser("CUSTOMER", fixture + "-cust@nv.test");

        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, venueId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", adminId, venueId);

        // Customers
        verifiedCustomerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, age_verified, age_verified_at, age_verified_by, created_at, updated_at)
                VALUES (?, true, ?, ?, ?, ?) RETURNING id
                """, Long.class, fixture + "-verified", now, cashierId, now, now);

        unverifiedCustomerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, age_verified, created_at, updated_at)
                VALUES (?, false, ?, ?) RETURNING id
                """, Long.class, fixture + "-unverified", now, now);

        customerId = verifiedCustomerId;

        // Products
        productId = jdbc.queryForObject("""
                INSERT INTO products (venue_id, sku, name, price, is_bottle_keepable, bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, 'Premium Gin', 1200000, true, 750, true, 0, ?, ?) RETURNING id
                """, Long.class, venueId, "SKU-" + fixture + "-1", now, now);

        nonKeepableProductId = jdbc.queryForObject("""
                INSERT INTO products (venue_id, sku, name, price, is_bottle_keepable, bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, 'Beer Can', 50000, false, null, true, 0, ?, ?) RETURNING id
                """, Long.class, venueId, "SKU-" + fixture + "-2", now, now);

        // Paid order with 1 order item
        orderId = createOrder(venueId, cashierId, customerId, "PAID", 1200000, 1200000, now);
        orderItemId = createOrderItem(orderId, productId, "PRODUCT", "Premium Gin", 1200000, 1, 1200000, "ORDERED", now);
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) return;
        synchronized (recordedKeys) {
            for (UUID key : recordedKeys) {
                jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key);
            }
            recordedKeys.clear();
        }
        jdbc.update("DELETE FROM bottle_vault_transactions WHERE vault_id IN (SELECT id FROM bottle_vaults WHERE venue_id IN (?, ?))", venueId, otherVenueId);
        jdbc.update("DELETE FROM bottle_vaults WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id IN (?, ?))", venueId, otherVenueId);
        jdbc.update("DELETE FROM orders WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM products WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)", verifiedCustomerId, unverifiedCustomerId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)", cashierId, managerId, adminId, customerUserId);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, otherVenueId);
    }

    @Test
    void validDepositReturns201AndCreatesDbRows(CapturedOutput output) throws Exception {
        UUID key = recordKey(UUID.randomUUID());

        MvcResult result = mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.labelCode").isNotEmpty())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.venueId").value(venueId))
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.originalVolumeMl").value(750))
                .andExpect(jsonPath("$.remainingVolumeMl").value(750))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiryDate").value("2026-03-31")) // 2026-03-01 + 30 days
                .andExpect(jsonPath("$.qrToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenAlreadyIssued").value(false))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long vaultId = json.path("vaultId").asLong();
        String rawToken = json.path("qrToken").asText();
        String labelCode = json.path("labelCode").asText();

        assertThat(labelCode).startsWith("BV-");
        assertThat(labelCode).hasSize(15); // "BV-" + 12 chars

        // Token decoding & hashing test
        byte[] decoded = Base64.getUrlDecoder().decode(rawToken);
        assertThat(decoded).hasSize(16);
        String calculatedHash = sha256Hex(rawToken);

        // Verify bottle_vaults row
        Map<String, Object> vaultRow = jdbc.queryForMap(
                "SELECT * FROM bottle_vaults WHERE id = ?", vaultId);
        assertThat(vaultRow.get("qr_token_hash")).isEqualTo(calculatedHash);
        assertThat(vaultRow.get("source_order_item_id")).isEqualTo(orderItemId);
        assertThat(vaultRow.get("status")).isEqualTo("ACTIVE");

        // Verify bottle_vault_transactions row
        Map<String, Object> txRow = jdbc.queryForMap(
                "SELECT * FROM bottle_vault_transactions WHERE vault_id = ?", vaultId);
        assertThat(txRow.get("type")).isEqualTo("DEPOSIT");
        assertThat(txRow.get("volume_delta_ml")).isEqualTo(750);
        assertThat(txRow.get("remaining_after_ml")).isEqualTo(750);
        assertThat(txRow.get("performed_by")).isEqualTo(cashierId);
        assertThat(txRow.get("owner_confirmed")).isEqualTo(false);

        // Verify audit_logs row
        Map<String, Object> auditRow = jdbc.queryForMap(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'BOTTLE_DEPOSIT'", vaultId);
        assertThat(auditRow.get("actor_user_id")).isEqualTo(cashierId);
        assertThat(auditRow.get("entity_type")).isEqualTo("BOTTLE_VAULT");
        String detailsJson = auditRow.get("details").toString();
        assertThat(objectMapper.readTree(detailsJson).path("label_code").asText()).isEqualTo(labelCode);

        // Leak assertions: raw token and hash must NEVER leak into audit_logs, idempotency_keys, or application logs
        assertThat(detailsJson).doesNotContain(rawToken);
        assertThat(detailsJson).doesNotContain(calculatedHash);

        Map<String, Object> idemRow = jdbc.queryForMap(
                "SELECT * FROM idempotency_keys WHERE key = ?", key);
        String idemResponseBody = idemRow.get("response_body").toString();
        assertThat(idemResponseBody).doesNotContain(rawToken);
        assertThat(idemResponseBody).doesNotContain(calculatedHash);
        assertThat(objectMapper.readTree(idemResponseBody).path("tokenAlreadyIssued").asBoolean()).isTrue();

        assertThat(output.getAll()).doesNotContain(rawToken);
        assertThat(output.getAll()).doesNotContain(calculatedHash);
    }

    @Test
    void replayReturnsSnapshotWithoutToken() throws Exception {
        UUID key = recordKey(UUID.randomUUID());

        // First call
        MvcResult firstResult = mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qrToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenAlreadyIssued").value(false))
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        long vaultId = firstJson.path("vaultId").asLong();

        // Replay with same key & payload
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vaultId").value(vaultId))
                .andExpect(jsonPath("$.qrToken").doesNotExist()) // null field omitted or null
                .andExpect(jsonPath("$.tokenAlreadyIssued").value(true));

        // Replay with same key but different payload -> 422
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, 999999L)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duplicateDepositOnSameItemWithDifferentKeyReturns409() throws Exception {
        UUID key1 = recordKey(UUID.randomUUID());
        UUID key2 = recordKey(UUID.randomUUID());

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isCreated());

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", key2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("VAULT_ALREADY_EXISTS"));
    }

    @Test
    void rejectsOrdersNotPaid() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        for (String status : List.of("OPEN", "PARTIALLY_PAID", "VOIDED")) {
            long badOrderId = createOrder(venueId, cashierId, customerId, status, 1200000, 0, now);
            long badItemId = createOrderItem(badOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);

            mvc.perform(post(BOTTLE_URL)
                            .header("Authorization", bearer(cashierId, "CASHIER"))
                            .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(depositJson(badOrderId, badItemId)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void rejectsWalkInCustomer() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long walkInOrderId = createOrder(venueId, cashierId, null, "PAID", 1200000, 1200000, now);
        long item = createOrderItem(walkInOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(walkInOrderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsUnverifiedCustomer() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long unverifiedOrderId = createOrder(venueId, cashierId, unverifiedCustomerId, "PAID", 1200000, 1200000, now);
        long item = createOrderItem(unverifiedOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(unverifiedOrderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsNonKeepableProduct() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long item = createOrderItem(orderId, nonKeepableProductId, "PRODUCT", "Beer", 50000, 1, 50000, "ORDERED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsQuantityNotOne() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long item = createOrderItem(orderId, productId, "PRODUCT", "Gin", 1200000, 2, 2400000, "ORDERED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsBottleRedemptionItem() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long item = createOrderItem(orderId, productId, "BOTTLE_REDEMPTION", "Gin Redemption", 0, 1, 0, "ORDERED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsVoidedOrderItem() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long item = createOrderItem(orderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "VOIDED", now);

        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, item)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsUnknownOrCrossOrderItems() throws Exception {
        Timestamp now = Timestamp.from(testClock.instant());
        long otherOrder = createOrder(venueId, cashierId, customerId, "PAID", 1200000, 1200000, now);
        long otherItem = createOrderItem(otherOrder, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);

        // Unknown order -> 404
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(999999L, orderItemId)))
                .andExpect(status().isNotFound());

        // Unknown item -> 404
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, 999999L)))
                .andExpect(status().isNotFound());

        // Cross order item -> 404
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, otherItem)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizationMatrix() throws Exception {
        // CASHIER -> 201 (verified in validDepositReturns201AndCreatesDbRows)

        // MANAGER -> 201 on new paid order
        Timestamp now = Timestamp.from(testClock.instant());
        long mOrderId = createOrder(venueId, managerId, customerId, "PAID", 1200000, 1200000, now);
        long mItemId = createOrderItem(mOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(managerId, "MANAGER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(mOrderId, mItemId)))
                .andExpect(status().isCreated());

        // ADMIN -> 201 on new paid order
        long aOrderId = createOrder(venueId, adminId, customerId, "PAID", 1200000, 1200000, now);
        long aItemId = createOrderItem(aOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(adminId, "ADMIN"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(aOrderId, aItemId)))
                .andExpect(status().isCreated());

        // CUSTOMER -> 403 Forbidden
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(customerUserId, "CUSTOMER"))
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isForbidden());

        // Cross-venue -> 404
        long crossOrderId = createOrder(otherVenueId, cashierId, customerId, "PAID", 1200000, 1200000, now);
        long crossItemId = createOrderItem(crossOrderId, productId, "PRODUCT", "Gin", 1200000, 1, 1200000, "ORDERED", now);
        mvc.perform(post(BOTTLE_URL)
                        .header("Authorization", bearer(cashierId, "CASHIER")) // cashier does not belong to otherVenueId
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(crossOrderId, crossItemId)))
                .andExpect(status().isNotFound());

        // Unauthenticated -> 401
        mvc.perform(post(BOTTLE_URL)
                        .header("Idempotency-Key", recordKey(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson(orderId, orderItemId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void piiColumnsAbsentFromSchema() {
        List<String> piiKeywords = List.of("nik", "ktp", "id_number", "identity");
        List<String> tables = List.of("bottle_vaults", "bottle_vault_transactions", "customers");

        for (String table : tables) {
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_name = ?
                    """, String.class, table);

            for (String column : columns) {
                for (String kw : piiKeywords) {
                    assertThat(column.toLowerCase()).doesNotContain(kw);
                }
            }
        }
    }

    @Test
    void isSourceOrderItemUniqueViolationMatchesConstraint() {
        SQLException sqlException = new SQLException("duplicate key value violates unique constraint \"uq_vault_source_item\"", "23505");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Conflict", sqlException);

        assertThat(BottleRepository.isSourceOrderItemUniqueViolation(dive)).isTrue();

        SQLException otherSqlEx = new SQLException("duplicate key value violates unique constraint \"other_uq\"", "23505");
        DataIntegrityViolationException otherDive = new DataIntegrityViolationException("Conflict", otherSqlEx);
        assertThat(BottleRepository.isSourceOrderItemUniqueViolation(otherDive)).isFalse();
    }

    private UUID recordKey(UUID key) {
        recordedKeys.add(key);
        return key;
    }

    private String depositJson(long orderId, long orderItemId) {
        return "{\"orderId\":" + orderId + ",\"orderItemId\":" + orderItemId + "}";
    }

    private long createTestUser(String role, String email) {
        Timestamp now = Timestamp.from(testClock.instant());
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'hash', 'User ' || ?, ?, true, 0, ?, ?) RETURNING id
                """, Long.class, email, role, role, now, now);
    }

    private long createOrder(long venue, long cashier, Long customer, String status, long totalDue, long paidTotal, Timestamp now) {
        String num = "NV-" + fixture + "-" + UUID.randomUUID().toString().substring(0, 4);
        return jdbc.queryForObject("""
                INSERT INTO orders (
                    venue_id, order_number, cashier_id, customer_id, business_date, status,
                    subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                    minimum_spend_snapshot, opened_at, created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, CURRENT_DATE, ?, ?, 0, 0, 0, ?, ?, 0, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, venue, num, cashier, customer, status, totalDue, totalDue, paidTotal, now, now, now);
    }

    private long createOrderItem(long order, long product, String itemType, String name, long unitPrice, int qty, long lineTotal, String status, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO order_items (
                    order_id, product_id, item_type, product_name_snapshot,
                    unit_price_snapshot, quantity, line_total, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, order, product, itemType, name, unitPrice, qty, lineTotal, status, now, now);
    }

    private String bearer(long userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(Instant.parse("2026-03-01T12:00:00Z"));
        }
    }

    static final class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

