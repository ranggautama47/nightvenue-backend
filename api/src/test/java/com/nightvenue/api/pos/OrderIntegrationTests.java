package com.nightvenue.api.pos;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, OrderIntegrationTests.ClockConfiguration.class})
class OrderIntegrationTests {
    private static final String ORDERS = "/api/v1/orders";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired OrderTestClock clock;
    @Autowired BusinessDateService businessDates;
    @Autowired TransactionTemplate transactions;

    private String fixture;
    private long venueId;
    private long otherVenueId;
    private long adminId;
    private long managerId;
    private long cashierId;
    private long customerUserId;
    private final Set<UUID> recordedIdempotencyKeys = Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void createFixtures() {
        fixture = "O5A-" + UUID.randomUUID().toString().substring(0, 8);
        clock.set(Instant.parse("2026-10-24T12:00:00Z"));
        venueId = createVenue(fixture + "-a", true);
        otherVenueId = createVenue(fixture + "-b", true);
        adminId = demoUserId("ADMIN");
        managerId = demoUserId("MANAGER");
        cashierId = demoUserId("CASHIER");
        customerUserId = demoUserId("CUSTOMER");
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, venueId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueId);
    }

    @AfterEach
    void removeFixtures() {
        if (fixture == null) {
            return;
        }
        jdbc.update("""
                DELETE FROM order_items
                WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN venues v ON v.id = o.venue_id
                    WHERE v.code LIKE ?
                )
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM orders
                WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixture + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", fixture + "%");
        jdbc.update("DELETE FROM venue_tables WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)",
                fixture + "%");
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)",
                fixture + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixture + "%");
        synchronized (recordedIdempotencyKeys) {
            for (UUID key : recordedIdempotencyKeys) {
                jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key);
            }
            recordedIdempotencyKeys.clear();
        }
    }

    @Test
    void postRoleMatrixAndWalkInRecord() throws Exception {
        for (String role : List.of("ADMIN", "MANAGER", "CASHIER")) {
            MvcResult result = mvc.perform(postOrder(bearer(userId(role), role), orderJson(venueId, null, null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.cashierId").value(userId(role)))
                    .andExpect(jsonPath("$.subtotal").value(0))
                    .andExpect(jsonPath("$.discountAmount").value(0))
                    .andExpect(jsonPath("$.serviceChargeAmount").value(0))
                    .andExpect(jsonPath("$.taxAmount").value(0))
                    .andExpect(jsonPath("$.totalDue").value(0))
                    .andExpect(jsonPath("$.paidTotal").value(0))
                    .andExpect(jsonPath("$.minimumSpendSnapshot").value(0))
                    .andExpect(jsonPath("$.items").doesNotExist())
                    .andReturn();
            assertTrue(result.getResponse().getContentAsString()
                    .matches("(?s).*\"orderNumber\":\"NV-\\d{8}-\\d{6}\".*"));
        }

        mvc.perform(postOrder(bearer(customerUserId, "CUSTOMER"), orderJson(venueId, null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(post(ORDERS).header("Authorization", bearer(customerUserId, "CUSTOMER"))
                                .contentType(MediaType.APPLICATION_JSON).content(orderJson(venueId, null, null)))
                        .andExpect(status().isBadRequest());
        mvc.perform(post(ORDERS).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(venueId, null, null)))
                        .andExpect(status().isUnauthorized());
    }

    @Test
    void managerAndCashierCannotCreateOrReadAnotherVenuesOrder() throws Exception {
        long otherOrderId = insertOrder(otherVenueId, null, "OPEN", "NV-20261024-800001", 0);
        for (String role : List.of("MANAGER", "CASHIER")) {
            String auth = bearer(userId(role), role);
            mvc.perform(postOrder(auth, orderJson(otherVenueId, null, null)))
                    .andExpect(status().isNotFound());
            mvc.perform(get(ORDERS + "/" + otherOrderId).header("Authorization", auth))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Resource not found"));
        }
    }

    @Test
    void createsTableOrderAndOccupiesTable() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 250_000);
        MvcResult result = mvc.perform(postOrder(bearer(managerId, "MANAGER"), orderJson(venueId, tableId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tableId").value(tableId))
                .andExpect(jsonPath("$.minimumSpendSnapshot").value(250_000))
                .andReturn();
        long orderId = jsonLong(result, "id");
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId));
    }

    @Test
    void rejectsUnavailableTablesWithSanitizedConflict() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        for (String tableStatus : List.of("OCCUPIED", "OUT_OF_SERVICE")) {
            long tableId = createTable(venueId, tableStatus, true, 0);
            assertSanitizedConflict(mvc.perform(postOrder(auth, orderJson(venueId, tableId, null))).andReturn());
        }
    }

    @Test
    void rejectsMissingCrossVenueAndInactiveTables() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        long otherTable = createTable(otherVenueId, "AVAILABLE", true, 0);
        long inactiveTable = createTable(venueId, "AVAILABLE", false, 0);

        mvc.perform(postOrder(auth, orderJson(venueId, Long.MAX_VALUE, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table does not exist in this venue"));
        mvc.perform(postOrder(auth, orderJson(venueId, otherTable, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table does not exist in this venue"));
        mvc.perform(postOrder(auth, orderJson(venueId, inactiveTable, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table does not exist in this venue"));
    }

    @Test
    void rejectsMissingCustomerAndInvalidRequest() throws Exception {
        mvc.perform(postOrder(bearer(cashierId, "CASHIER"), orderJson(venueId, null, Long.MAX_VALUE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Customer does not exist"));
        mvc.perform(postOrder(bearer(cashierId, "CASHIER"), "{\"venueId\":0,\"tableId\":null,\"customerId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
        mvc.perform(postOrder(bearer(cashierId, "CASHIER"), "{\"tableId\":null,\"customerId\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsOrderWithExistingCustomer() throws Exception {
        long customerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, created_at, updated_at)
                VALUES (?, now(), now())
                RETURNING id
                """, Long.class, fixture + "-customer");
        MvcResult result = mvc.perform(postOrder(bearer(cashierId, "CASHIER"), orderJson(venueId, null, customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andReturn();

        long orderId = jsonLong(result, "id");
        assertEquals(customerId, jdbc.queryForObject(
                "SELECT customer_id FROM orders WHERE id = ?", Long.class, orderId));
    }

    @Test
    void bodyCashierIdCannotOverrideJwtIdentity() throws Exception {
        String body = orderJson(venueId, null, null).replace("}", ",\"cashierId\":" + managerId + "}");
        MvcResult result = mvc.perform(postOrder(bearer(cashierId, "CASHIER"), body))
                .andExpect(status().isCreated())
                .andReturn();
        long orderId = jsonLong(result, "id");
        assertEquals(cashierId, jdbc.queryForObject(
                "SELECT cashier_id FROM orders WHERE id = ?", Long.class, orderId));
    }

    @Test
    void missingAndInactiveVenuesReturnNotFound() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        mvc.perform(postOrder(auth, orderJson(Long.MAX_VALUE, null, null)))
                .andExpect(status().isNotFound());

        long inactiveVenue = createVenue(fixture + "-inactive", false);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, inactiveVenue);
        mvc.perform(postOrder(auth, orderJson(inactiveVenue, null, null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrderIsVenueScopedAndMissingOrdersAreNotFound() throws Exception {
        long ownOrder = insertOrder(venueId, null, "OPEN", "NV-20261024-800002", 0);
        long otherOrder = insertOrder(otherVenueId, null, "OPEN", "NV-20261024-800003", 0);
        mvc.perform(get(ORDERS + "/" + ownOrder).header("Authorization", bearer(managerId, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownOrder));
        mvc.perform(get(ORDERS + "/" + otherOrder).header("Authorization", bearer(managerId, "MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));
        mvc.perform(get(ORDERS + "/" + Long.MAX_VALUE).header("Authorization", bearer(adminId, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));
    }

    @Test
    void getOrderRoleMatrix() throws Exception {
        long ownOrder = insertOrder(venueId, null, "OPEN", "NV-20261024-800011", 0);
        long otherOrder = insertOrder(otherVenueId, null, "OPEN", "NV-20261024-800012", 0);

        for (String role : List.of("ADMIN", "MANAGER", "CASHIER")) {
            mvc.perform(get(ORDERS + "/" + ownOrder).header("Authorization", bearer(userId(role), role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ownOrder));
        }
        mvc.perform(get(ORDERS + "/" + ownOrder)
                        .header("Authorization", bearer(customerUserId, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/" + ownOrder)).andExpect(status().isUnauthorized());
        for (String role : List.of("MANAGER", "CASHIER")) {
            mvc.perform(get(ORDERS + "/" + otherOrder).header("Authorization", bearer(userId(role), role)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Resource not found"));
        }
        mvc.perform(get(ORDERS + "/" + otherOrder).header("Authorization", bearer(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(otherOrder));
    }

    @Test
    void persistsBusinessDateAtTheJakartaCutoffBoundary() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        assertBusinessDate(auth, Instant.parse("2026-10-23T19:30:00Z"), "2026-10-23");
        assertBusinessDate(auth, Instant.parse("2026-10-23T22:59:59Z"), "2026-10-23");
        assertBusinessDate(auth, Instant.parse("2026-10-23T23:00:00Z"), "2026-10-24");
    }

    @Test
    void simultaneousOrdersForOneTableProduceOneSuccessAndSevenConflicts() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        String auth = bearer(cashierId, "CASHIER");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(postOrder(auth, orderJson(venueId, tableId, null)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> outcome : outcomes) {
                statuses.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(value -> value == 201).count());
            assertEquals(7, statuses.stream().filter(value -> value == 409).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders
                WHERE table_id = ? AND status IN ('OPEN', 'PARTIALLY_PAID')
                """, Integer.class, tableId));
    }

    @Test
    void simultaneousOrdersForDifferentTablesBothSucceed() throws Exception {
        long firstTable = createTable(venueId, "AVAILABLE", true, 0);
        long secondTable = createTable(venueId, "AVAILABLE", true, 0);
        String auth = bearer(cashierId, "CASHIER");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> createAtGate(auth, firstTable, ready, start));
            Future<Integer> second = executor.submit(() -> createAtGate(auth, secondTable, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(201, first.get(30, TimeUnit.SECONDS));
            assertEquals(201, second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseEnforcesActiveTableAndOrderNumberUniquenessAndChecks() {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        insertOrder(venueId, tableId, "OPEN", "NV-20261024-800004", 0);
        assertConstraintViolation("uq_orders_active_table",
                () -> insertOrder(venueId, tableId, "OPEN", "NV-20261024-800005", 0));

        assertConstraintViolation("orders_status_check",
                () -> insertOrder(venueId, null, "NOT_A_STATUS", "NV-20261024-800006", 0));
        assertConstraintViolation("orders_subtotal_check",
                () -> insertOrder(venueId, null, "OPEN", "NV-20261024-800007", -1));
        insertOrder(venueId, null, "OPEN", "NV-20261024-800008", 0);
        assertConstraintViolation("uq_orders_venue_number",
                () -> insertOrder(venueId, null, "OPEN", "NV-20261024-800008", 0));
    }

    @Test
    void activeTableIndexViolationReturnsSanitizedConflict() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        insertOrder(venueId, tableId, "OPEN", "NV-20261024-800010", 0);

        MvcResult result = mvc.perform(postOrder(bearer(managerId, "MANAGER"), orderJson(venueId, tableId, null)))
                .andExpect(status().isConflict())
                .andReturn();
        assertSanitizedConflict(result);
    }

    @Test
    void paidOrderDoesNotPreventAnotherActiveOrderOnTheSameTable() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        insertOrder(venueId, tableId, "PAID", "NV-20261024-800009", 0);

        mvc.perform(postOrder(bearer(managerId, "MANAGER"), orderJson(venueId, tableId, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void openedOrderBlocksManualTableReleaseAndDelete() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        mvc.perform(postOrder(bearer(managerId, "MANAGER"), orderJson(venueId, tableId, null)))
                .andExpect(status().isCreated());
        String auth = bearer(managerId, "MANAGER");

        mvc.perform(put("/api/v1/tables/" + tableId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table has an active order"));
        mvc.perform(delete("/api/v1/tables/" + tableId).header("Authorization", auth))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table has an active order"));

        assertEquals("OCCUPIED", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
        assertTrue(jdbc.queryForObject(
                "SELECT is_active FROM venue_tables WHERE id = ?", Boolean.class, tableId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE table_id = ? AND status IN ('OPEN', 'PARTIALLY_PAID')",
                Integer.class, tableId));
    }

    @Test
    void failedTableOccupancyRollsBackInsertedOrder() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        String functionName = "phase5a_suppress_table_" + tableId;
        String triggerName = "phase5a_suppress_occupy_" + tableId;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.status = 'OCCUPIED' THEN
                        RETURN NULL;
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(functionName, tableId));
        jdbc.execute("""
                CREATE TRIGGER %s
                BEFORE UPDATE OF status ON venue_tables
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
        UUID rollbackKey = UUID.randomUUID();
        try {
            mvc.perform(postOrder(bearer(managerId, "MANAGER"), rollbackKey, orderJson(venueId, tableId, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Table is not available"));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, rollbackKey));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON venue_tables");
            jdbc.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE venue_id = ?",
                Integer.class, venueId));
        assertEquals("AVAILABLE", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
    }

    @Test
    void orderSchemaMatchesDatabaseDoc() {
        // 1) pg_indexes: ix_orders_venue_business_date_status has indexdef containing "(venue_id, business_date, status)"
        String indexDef = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE indexname = 'ix_orders_venue_business_date_status'
                """, String.class);
        assertNotNull(indexDef, "Index ix_orders_venue_business_date_status must exist");
        assertTrue(indexDef.contains("(venue_id, business_date, status)"),
                "Index definition must contain columns in order (venue_id, business_date, status): " + indexDef);

        // 2) information_schema.columns: order_items.updated_at has is_nullable='NO' and data_type='timestamp with time zone'
        Map<String, Object> col = jdbc.queryForMap("""
                SELECT is_nullable, data_type
                FROM information_schema.columns
                WHERE table_name = 'order_items' AND column_name = 'updated_at'
                """);
        assertNotNull(col, "Column order_items.updated_at must exist");
        assertEquals("NO", col.get("is_nullable"), "order_items.updated_at must be NOT NULL");
        assertEquals("timestamp with time zone", col.get("data_type"),
                "order_items.updated_at must be TIMESTAMPTZ");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postOrder(String auth, String body) {
        UUID key = UUID.randomUUID();
        return postOrder(auth, key, body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postOrder(String auth, UUID key, String body) {
        if (key != null) {
            recordedIdempotencyKeys.add(key);
        }
        var builder = post(ORDERS).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            builder.header("Idempotency-Key", key.toString());
        }
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return builder;
    }

    private void assertBusinessDate(String auth, Instant instant, String expected) throws Exception {
        clock.set(instant);
        MvcResult result = mvc.perform(postOrder(auth, orderJson(venueId, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value(expected))
                .andReturn();
        long id = jsonLong(result, "id");
        assertEquals(expected, jdbc.queryForObject(
                "SELECT business_date::text FROM orders WHERE id = ?", String.class, id));
        Instant openedAt = jdbc.queryForObject(
                "SELECT opened_at FROM orders WHERE id = ?", (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        LocalDate storedBusinessDate = jdbc.queryForObject(
                "SELECT business_date FROM orders WHERE id = ?", (rs, row) -> rs.getObject(1, LocalDate.class), id);
        assertEquals(storedBusinessDate,
                businessDates.businessDate(openedAt, ZoneId.of("Asia/Jakarta"), 6));
    }

    private int createAtGate(
            String auth, long tableId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mvc.perform(postOrder(auth, orderJson(venueId, tableId, null)))
                .andReturn().getResponse().getStatus();
    }

    private void assertSanitizedConflict(MvcResult result) throws Exception {
        assertEquals(409, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"detail\":\"Table is not available\""));
        assertFalse(body.contains("tableId"));
        assertFalse(body.contains("orderId"));
        assertFalse(body.contains("uq_orders_active_table"));
        assertFalse(body.contains("SQLState"));
        assertFalse(body.contains("23505"));
    }

    private void assertConstraintViolation(String constraint, Runnable operation) {
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation::run);
        String message = exception.getMostSpecificCause().getMessage();
        assertTrue(message.contains(constraint), message);
    }

    private long createVenue(String code, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO venues (
                    code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at
                )
                VALUES (?, ?, 'Asia/Jakarta', 6, ?, now(), now())
                RETURNING id
                """, Long.class, code, code, active);
    }

    private long createTable(long venue, String status, boolean active, long minimumSpend) {
        return jdbc.queryForObject("""
                INSERT INTO venue_tables (
                    venue_id, code, capacity, minimum_spend, status, is_active, version
                )
                VALUES (?, ?, 4, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, venue, fixture.substring(0, 7) + "-" + UUID.randomUUID().toString().substring(0, 8),
                minimumSpend, status, active);
    }

    private long insertOrder(long venue, Long table, String status, String orderNumber, long subtotal) {
        return jdbc.queryForObject("""
                INSERT INTO orders (
                    venue_id, order_number, table_id, cashier_id, business_date, status,
                    subtotal, total_due, opened_at, created_at, updated_at, version
                )
                VALUES (?, ?, ?::bigint, ?, DATE '2026-10-24', ?, ?, 0, now(), now(), now(), 0)
                RETURNING id
                """, Long.class, venue, orderNumber, table, cashierId, status, subtotal);
    }

    private long demoUserId(String role) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                role.toLowerCase() + "@nightvenue.demo");
    }

    private long userId(String role) {
        return switch (role) {
            case "ADMIN" -> adminId;
            case "MANAGER" -> managerId;
            case "CASHIER" -> cashierId;
            default -> throw new IllegalArgumentException("Unknown order role");
        };
    }

    private String bearer(long userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String orderJson(long venue, Long table, Long customer) {
        return "{\"venueId\":" + venue
                + ",\"tableId\":" + (table == null ? "null" : table)
                + ",\"customerId\":" + (customer == null ? "null" : customer) + "}";
    }

    private long jsonLong(MvcResult result, String field) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path(field).asLong();
    }

    @Test
    void replayTableOrderSameKey() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 100_000);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, tableId, null);

        MvcResult first = mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstResponse = first.getResponse().getContentAsString();

        long versionBeforeReplay = jdbc.queryForObject(
                "SELECT version FROM venue_tables WHERE id = ?", Long.class, tableId);

        MvcResult second = mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated())
                .andReturn();
        String secondResponse = second.getResponse().getContentAsString();

        long versionAfterReplay = jdbc.queryForObject(
                "SELECT version FROM venue_tables WHERE id = ?", Long.class, tableId);

        assertEquals(firstResponse, secondResponse);
        assertEquals(versionBeforeReplay, versionAfterReplay);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
    }

    @Test
    void replayWalkInSameKey() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, null, null);

        MvcResult first = mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstResponse = first.getResponse().getContentAsString();

        MvcResult second = mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated())
                .andReturn();
        String secondResponse = second.getResponse().getContentAsString();

        assertEquals(firstResponse, secondResponse);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    @Test
    void walkInDifferentKeysCreateTwoOrders() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        mvc.perform(postOrder(auth, orderJson(venueId, null, null)))
                .andExpect(status().isCreated());
        mvc.perform(postOrder(auth, orderJson(venueId, null, null)))
                .andExpect(status().isCreated());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'ORDER_OPEN' AND user_id = ?
                """, Integer.class, cashierId));
    }

    @Test
    void sameKeyDifferentPayloadReturns422() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        long versionBefore = jdbc.queryForObject(
                "SELECT version FROM venue_tables WHERE id = ?", Long.class, tableId);

        mvc.perform(postOrder(auth, key, orderJson(venueId, null, null)))
                .andExpect(status().isCreated());

        mvc.perform(postOrder(auth, key, orderJson(venueId, tableId, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key reused with a different payload"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals("AVAILABLE", jdbc.queryForObject(
                "SELECT status FROM venue_tables WHERE id = ?", String.class, tableId));
        assertEquals(versionBefore, jdbc.queryForObject(
                "SELECT version FROM venue_tables WHERE id = ?", Long.class, tableId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    @Test
    void concurrentSameKeyTable() throws Exception {
        long tableId = createTable(venueId, "AVAILABLE", true, 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, tableId, null);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(postOrder(auth, key, body)).andReturn();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }

            assertEquals(8, results.stream().filter(r -> r.getResponse().getStatus() == 201).count());
            String expectedBody = results.get(0).getResponse().getContentAsString();
            for (MvcResult res : results) {
                assertEquals(expectedBody, res.getResponse().getContentAsString());
            }
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyWalkIn() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, null, null);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(postOrder(auth, key, body)).andReturn();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }

            assertEquals(8, results.stream().filter(r -> r.getResponse().getStatus() == 201).count());
            String expectedBody = results.get(0).getResponse().getContentAsString();
            for (MvcResult res : results) {
                assertEquals(expectedBody, res.getResponse().getContentAsString());
            }
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retryAfter409WithSameKeySucceeds() throws Exception {
        long tableId = createTable(venueId, "OCCUPIED", true, 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, tableId, null);

        mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isConflict());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));

        jdbc.update("UPDATE venue_tables SET status = 'AVAILABLE' WHERE id = ?", tableId);

        mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    @Test
    void missingAndInvalidHeaderReturn400() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        String body = orderJson(venueId, null, null);

        mvc.perform(post(ORDERS).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        mvc.perform(post(ORDERS).header("Authorization", auth).header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE scope = 'ORDER_OPEN'", Integer.class));
    }

    @Test
    void sameKeyDifferentUsersCreateSeparateOrders() throws Exception {
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, null, null);

        mvc.perform(postOrder(bearer(managerId, "MANAGER"), key, body))
                .andExpect(status().isCreated());

        mvc.perform(postOrder(bearer(cashierId, "CASHIER"), key, body))
                .andExpect(status().isCreated());

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE venue_id = ?", Integer.class, venueId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    @Test
    void idempotencyScopeCheck() {
        List<String> columns = jdbc.query("""
                SELECT column_name, data_type, is_nullable,
                       COALESCE(column_default, '<null>') AS column_default
                FROM information_schema.columns
                WHERE table_name = 'idempotency_keys'
                ORDER BY ordinal_position
                """, (rs, row) -> String.join("|",
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("is_nullable"),
                rs.getString("column_default")));
        String isolation = transactions.execute(status ->
                jdbc.queryForObject("SHOW transaction_isolation", String.class));
        assertEquals("read committed", isolation);
        assertTrue(columns.contains("response_status|integer|YES|<null>"));
        assertTrue(columns.contains("response_body|jsonb|YES|<null>"));

        for (String scope : List.of("ORDER_ADD_ITEMS", "PAYMENT_CREATE", "ORDER_VOID", "BOTTLE_DEPOSIT", "BOTTLE_REDEEM", "ORDER_OPEN")) {
            UUID rowKey = UUID.randomUUID();
            recordedIdempotencyKeys.add(rowKey);
            jdbc.update("INSERT INTO idempotency_keys (key, scope, user_id, request_hash, created_at) VALUES (?, ?, ?, ?, now())",
                    rowKey, scope, cashierId, "a".repeat(64));
        }

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO idempotency_keys (key, scope, user_id, request_hash, created_at) VALUES (?, ?, ?, ?, now())",
                        UUID.randomUUID(), "INVALID_SCOPE", cashierId, "a".repeat(64)));
        assertTrue(ex.getMostSpecificCause().getMessage().contains("idempotency_keys_scope_check"),
                ex.getMostSpecificCause().getMessage());
    }

    @Test
    void replayDoesNotConsumeOrderNumberSequence() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        String body = orderJson(venueId, null, null);

        mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated());

        long seqBefore = jdbc.queryForObject("SELECT last_value FROM order_number_seq", Long.class);

        mvc.perform(postOrder(auth, key, body))
                .andExpect(status().isCreated());

        long seqAfter = jdbc.queryForObject("SELECT last_value FROM order_number_seq", Long.class);

        assertEquals(seqBefore, seqAfter);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        OrderTestClock orderTestClock() {
            return new OrderTestClock(Instant.parse("2026-10-24T12:00:00Z"));
        }
    }

    static final class OrderTestClock extends Clock {
        private final AtomicReference<Instant> instant;

        OrderTestClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
