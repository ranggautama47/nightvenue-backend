package com.nightvenue.api.inventory;

import com.nightvenue.api.common.Conflict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryConcurrencyTests extends InventoryIntegrationTestSupport {
    private static final String INVENTORY = "/api/v1/inventory";
    private static final int WORKERS = 50;

    @Test
    @DisplayName("4C-21 fiftyAdjustsSameExpectedVersionOneSuccessFortyNineConflict")
    void fiftyAdjustsSameExpectedVersionOneSuccessFortyNineConflict() throws Exception {
        jdbc.update("UPDATE inventory_items SET quantity = 1000.000, version = 0 WHERE id = ?", inventoryItemId);
        String manager = bearer("MANAGER");
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                responses.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workers did not start");
                    }
                    return mvc.perform(MockMvcRequestBuilders.post(
                                    INVENTORY + "/" + inventoryItemId + "/adjust")
                                    .header("Authorization", manager)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(adjustJson("ADJUSTMENT", "-20.000", "count", 0L)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            start.countDown();
            int succeeded = 0;
            int conflicted = 0;
            for (Future<Integer> response : responses) {
                int statusCode = response.get(60, TimeUnit.SECONDS);
                if (statusCode == 200) succeeded++;
                else if (statusCode == 409) conflicted++;
                else throw new AssertionError("Unexpected HTTP status: " + statusCode);
            }
            assertEquals(1, succeeded);
            assertEquals(49, conflicted);
        } finally {
            workers.shutdownNow();
        }
        assertEquals(new BigDecimal("980.000"), jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, inventoryItemId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM inventory_items WHERE id = ?", Long.class, inventoryItemId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE inventory_item_id = ?",
                Integer.class, inventoryItemId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE entity_type = 'INVENTORY_ITEM' AND entity_id = ?
                """, Integer.class, inventoryItemId));
    }

    @Test
    @DisplayName("4C-22 fiftyInternalDeductsStockForTenExactlyTenSuccessFortyConflict")
    void fiftyInternalDeductsStockForTenExactlyTenSuccessFortyConflict() throws Exception {
        jdbc.update("UPDATE inventory_items SET quantity = 10.000, version = 0 WHERE id = ?", inventoryItemId);
        long actor = demoUserId("ADMIN");
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workers did not start");
                    }
                    try {
                        transactions.execute(tx -> {
                            deduct.deductForReference(
                                venueId, barLocationId,
                                List.of(new InventoryDeductService.IngredientQty(
                                        ingredientId, new BigDecimal("1.000"))),
                                "ORDER_ITEM", 9_000L, LocalDate.parse("2026-10-23"), actor);
                            return null;
                        });
                        return true;
                    } catch (Conflict conflict) {
                        assertEquals("Insufficient stock", conflict.getMessage());
                        return false;
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            start.countDown();
            int committed = 0;
            int insufficient = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) committed++;
                else insufficient++;
            }
            assertEquals(10, committed);
            assertEquals(40, insufficient);
        } finally {
            workers.shutdownNow();
        }

        assertEquals(new BigDecimal("0.000"), jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, inventoryItemId));
        assertFalse(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM inventory_items WHERE id = ? AND quantity < 0)",
                Boolean.class, inventoryItemId));
        List<BigDecimal> balances = jdbc.query("""
                SELECT balance_after FROM inventory_transactions
                WHERE inventory_item_id = ? AND type = 'SALE_DEDUCT'
                ORDER BY id ASC
                """, (rs, row) -> rs.getBigDecimal(1), inventoryItemId);
        assertEquals(10, balances.size());
        for (int i = 0; i < balances.size(); i++) {
            assertEquals(new BigDecimal(9 - i + ".000"), balances.get(i));
        }
    }

    @Test
    @DisplayName("4C-23 internalDeductUsesIngredientIdAscending")
    void internalDeductUsesIngredientIdAscending() {
        long lowId = createIngredient(fixture + "-low", true);
        long highId = createIngredient(fixture + "-high", true);
        createInventoryItem(barLocationId, lowId, "5.000", "0.000", 0);
        createInventoryItem(barLocationId, highId, "5.000", "0.000", 0);
        transactions.execute(tx -> {
            deduct.deductForReference(
                    venueId, barLocationId,
                    List.of(new InventoryDeductService.IngredientQty(highId, new BigDecimal("1.000")),
                            new InventoryDeductService.IngredientQty(lowId, new BigDecimal("1.000"))),
                    "ORDER_ITEM", 9_001L, LocalDate.parse("2026-10-23"), demoUserId("ADMIN"));
            return null;
        });
        List<Long> processed = jdbc.query("""
                SELECT i.ingredient_id
                FROM inventory_transactions t JOIN inventory_items i ON i.id = t.inventory_item_id
                WHERE t.reference_id = 9001 AND t.type = 'SALE_DEDUCT'
                ORDER BY t.id
                """, (rs, row) -> rs.getLong(1));
        assertEquals(List.of(lowId, highId), processed);
    }

    @Test
    @DisplayName("4C-44 twoDeductsInOneTransactionSecondFailsFirstRollsBack")
    void twoDeductsInOneTransactionSecondFailsFirstRollsBack() {
        long firstIngredient = createIngredient(fixture + "-first", true);
        long laterIngredient = createIngredient(fixture + "-later", true);
        long firstItem = createInventoryItem(barLocationId, firstIngredient, "5.000", "0.000", 0);
        long laterItem = createInventoryItem(barLocationId, laterIngredient, "0.000", "0.000", 0);
        assert firstIngredient < laterIngredient;

        assertThrows(Conflict.class, () -> transactions.execute(tx -> {
            deduct.deductForReference(
                    venueId, barLocationId,
                    List.of(new InventoryDeductService.IngredientQty(laterIngredient, new BigDecimal("1.000")),
                            new InventoryDeductService.IngredientQty(firstIngredient, new BigDecimal("1.000"))),
                    "ORDER_ITEM", 9_002L, LocalDate.parse("2026-10-23"), demoUserId("ADMIN"));
            return null;
        }));

        assertEquals(new BigDecimal("5.000"), jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, firstItem));
        assertEquals(new BigDecimal("0.000"), jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, laterItem));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM inventory_transactions WHERE reference_id = 9002 AND type = 'SALE_DEDUCT'
                """, Integer.class));
    }

    @Test
    @DisplayName("4C-45 internalDeductMissingBarRow409")
    void internalDeductMissingBarRow409() {
        long missing = createIngredient(fixture + "-missing-bar-row", true);
        Conflict conflict = assertThrows(Conflict.class, () -> transactions.execute(tx -> {
            deduct.deductForReference(
                        venueId, barLocationId,
                        List.of(new InventoryDeductService.IngredientQty(missing, new BigDecimal("1.000"))),
                        "ORDER_ITEM", 9_003L, LocalDate.parse("2026-10-23"), demoUserId("ADMIN"));
            return null;
        }));
        assertEquals("Insufficient stock", conflict.getMessage());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE reference_id = 9003",
                Integer.class));
    }
}
