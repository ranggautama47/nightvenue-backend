package com.nightvenue.api.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryIntegrationTests extends InventoryIntegrationTestSupport {
    @Autowired
    JwtDecoder jwtDecoder;
    private static final String LOCATIONS = "/api/v1/stock-locations";
    private static final String INVENTORY = "/api/v1/inventory";

    @Test
    @DisplayName("4C-02 cashierCanReadInventoryAndLocationsButCannotAdjustProvisionOrMutateLocations")
    void cashierCanReadInventoryAndLocationsButCannotAdjustProvisionOrMutateLocations() throws Exception {
        String cashier = bearer("CASHIER");
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS).param("venueId", Long.toString(venueId))
                        .header("Authorization", cashier))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS + "/" + barLocationId).header("Authorization", cashier))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY).param("venueId", Long.toString(venueId))
                        .header("Authorization", cashier))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId).header("Authorization", cashier))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.post(INVENTORY + "/" + inventoryItemId + "/adjust")
                        .header("Authorization", cashier).contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson("PURCHASE_IN", "1", null, 0L)))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(INVENTORY).header("Authorization", cashier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locationId", barLocationId, "ingredientId", ingredientId, "reorderLevel", "1"))))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS).header("Authorization", cashier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-cashier", "type", "WAREHOUSE"))))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.put(LOCATIONS + "/" + warehouseLocationId)
                        .header("Authorization", cashier).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-mutated", "type", "WAREHOUSE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("4C-03 cashierForbiddenOnTransactionHistory")
    void cashierForbiddenOnTransactionHistory() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions")
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("4C-04 managerOutOfVenueReturns404")
    void managerOutOfVenueReturns404() throws Exception {
        long otherIngredient = createIngredient(fixture + "-other-ingredient", true);
        long otherItem = createInventoryItem(otherBarLocationId, otherIngredient, "0.000", "0.000", 0);
        String manager = bearer("MANAGER");
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", manager))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS + "/" + otherBarLocationId).header("Authorization", manager))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", manager))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + otherItem).header("Authorization", manager))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + otherItem + "/transactions")
                        .header("Authorization", manager))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post(INVENTORY + "/" + otherItem + "/adjust")
                        .header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson("PURCHASE_IN", "1", null, 0L)))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post(INVENTORY).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locationId", otherBarLocationId, "ingredientId", otherIngredient,
                                "reorderLevel", "0"))))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-other", "type", "WAREHOUSE"))))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.put(LOCATIONS + "/" + otherBarLocationId)
                        .header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-other-renamed", "type", "BAR"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("4C-05 adminCrossVenueReadOnActiveVenue")
    void adminCrossVenueReadOnActiveVenue() throws Exception {
        long otherIngredient = createIngredient(fixture + "-admin-other-ingredient", true);
        long otherItem = createInventoryItem(otherBarLocationId, otherIngredient, "0.000", "2.000", 0);
        String admin = bearer("ADMIN");
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + otherItem + ")]").exists());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + otherItem).header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("4C-31 adminCreatesZeroQuantityInventoryItem201")
    void adminCreatesZeroQuantityInventoryItem201() throws Exception {
        long ingredient = createIngredient(fixture + "-provision", true);
        mvc.perform(MockMvcRequestBuilders.post(INVENTORY)
                        .header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locationId", barLocationId,
                                "ingredientId", ingredient,
                                "reorderLevel", "100.000", "quantity", "900.000"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(barLocationId))
                .andExpect(jsonPath("$.ingredientId").isNumber())
                .andExpect(jsonPath("$.quantity").value("0.000"))
                .andExpect(jsonPath("$.reorderLevel").value("100.000"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("4C-32 managerCreatesInsideAssignedVenue201")
    void managerCreatesInsideAssignedVenue201() throws Exception {
        mvc.perform(provisionRequest("MANAGER", barLocationId, createIngredient(fixture + "-manager", true), "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value("0.000"));
    }

    @Test
    @DisplayName("4C-33 managerCrossVenueLocation404")
    void managerCrossVenueLocation404() throws Exception {
        long otherIngredient = createIngredient(fixture + "-cross", true);
        mvc.perform(provisionRequest("MANAGER", otherBarLocationId, otherIngredient, "0"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("4C-34 cashierProvision403")
    void cashierProvision403() throws Exception {
        mvc.perform(provisionRequest("CASHIER", barLocationId, ingredientId, "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("4C-35 missingIngredient422")
    void missingIngredient422() throws Exception {
        mvc.perform(provisionRequest("MANAGER", barLocationId, Long.MAX_VALUE, "0"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("4C-36 inactiveIngredient422")
    void inactiveIngredient422() throws Exception {
        long inactive = createIngredient(fixture + "-inactive", false);
        mvc.perform(provisionRequest("MANAGER", barLocationId, inactive, "0"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("4C-37 duplicateLocationIngredient409Sanitized")
    void duplicateLocationIngredient409Sanitized() throws Exception {
        var result = mvc.perform(provisionRequest("MANAGER", barLocationId, ingredientId, "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Inventory item already exists"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("uq_inventory"));
        assertFalse(result.getResponse().getContentAsString().contains("SQL"));
    }

    @Test
    @DisplayName("4C-38 provisionDoesNotWriteLedger")
    void provisionDoesNotWriteLedger() throws Exception {
        long ingredient = createIngredient(fixture + "-no-ledger", true);
        mvc.perform(provisionRequest("ADMIN", barLocationId, ingredient, "0"))
                .andExpect(status().isCreated());
        assertEquals(0, transactionCountForIngredient(ingredient));
    }

    @Test
    @DisplayName("4C-39 missingLocationOnProvision404")
    void missingLocationOnProvision404() throws Exception {
        mvc.perform(provisionRequest("MANAGER", Long.MAX_VALUE, ingredientId, "0"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("4C-12 purchaseInIncreasesQuantityAndWritesLedgerBalanceAfter")
    void purchaseInIncreasesQuantityAndWritesLedgerBalanceAfter() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "0.125", null, 0L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousQuantity").value(0))
                .andExpect(jsonPath("$.newQuantity").value(0.125))
                .andExpect(jsonPath("$.delta").value(0.125))
                .andExpect(jsonPath("$.type").value("PURCHASE_IN"));
        assertEquals(new BigDecimal("0.125"), jdbc.queryForObject(
                "SELECT balance_after FROM inventory_transactions WHERE inventory_item_id = ?",
                BigDecimal.class, inventoryItemId));
        assertEquals(1, transactionCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("4C-13 wasteWithoutReason422DoesNotWriteLedger")
    void wasteWithoutReason422DoesNotWriteLedger() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "WASTE", "-0.125", null, 0L))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, transactionCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("4C-14 wasteThatWouldGoNegative409NoLedger")
    void wasteThatWouldGoNegative409NoLedger() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "WASTE", "-0.125", "spillage", 0L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient stock"));
        assertEquals(0, transactionCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("purchaseInAndWasteRejectWrongDeltaSigns")
    void purchaseInAndWasteRejectWrongDeltaSigns() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "-1", null, 0L))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(adjustRequest("MANAGER", "WASTE", "1", "invalid positive waste", 0L))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, transactionCountForItem(inventoryItemId));
        assertEquals(0, auditCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("4C-15 adjustmentReasonRequired422")
    void adjustmentReasonRequired422() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "ADJUSTMENT", "0.125", " ", 0L))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, transactionCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("4C-16 ledgerBusinessDateMatchesBusinessDateServiceCutoffCase")
    void ledgerBusinessDateMatchesBusinessDateServiceCutoffCase() throws Exception {
        String[] times = {"2026-10-23T19:30:00Z", "2026-10-23T22:59:59Z", "2026-10-23T23:00:00Z"};
        String[] dates = {"2026-10-23", "2026-10-23", "2026-10-24"};
        for (int i = 0; i < times.length; i++) {
            clock.set(Instant.parse(times[i]));
            long version = jdbc.queryForObject(
                    "SELECT version FROM inventory_items WHERE id = ?", Long.class, inventoryItemId);
            mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "1.000", null, version))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.businessDate").value(dates[i]));
        }
    }

    @Test
    @DisplayName("4C-17 quantityRoundTripBigDecimalNotFloat")
    void quantityRoundTripBigDecimalNotFloat() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "0.125", null, 0L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(0.125));
        BigDecimal quantity = jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, inventoryItemId);
        assertEquals(new BigDecimal("0.125"), quantity);
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId)
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(0.125));
    }

    @Test
    @DisplayName("4C-18 postgresEnforcesQuantityNonNegativeAndItemUniqueness")
    void postgresEnforcesQuantityNonNegativeAndItemUniqueness() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory_items SET quantity = -1 WHERE id = ?", inventoryItemId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version)
                VALUES (?, ?, 0, 0, 0)
                """, barLocationId, ingredientId));
    }

    @Test
    @DisplayName("4C-19 saleDeductNotAcceptedOnHttpAdjust400")
    void saleDeductNotAcceptedOnHttpAdjust400() throws Exception {
        for (String type : new String[]{"SALE_DEDUCT", "VOID_RETURN"}) {
            mvc.perform(adjustRequest("MANAGER", type, "-1", "not public", 0L))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Request validation failed"));
        }
    }

    @Test
    @DisplayName("4C-20 missingInventoryId404")
    void missingInventoryId404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + Long.MAX_VALUE)
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("4C-40 expectedVersionMismatch409NoLedger")
    void expectedVersionMismatch409NoLedger() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "1", null, 5L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Inventory version conflict"));
        assertEquals(0, transactionCountForItem(inventoryItemId));
        assertEquals(0, auditCountForItem(inventoryItemId));
    }

    @Test
    @DisplayName("4C-41 deltaZero400")
    void deltaZero400() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "0.000", null, 0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    @Test
    @DisplayName("4C-42 auditRowWrittenInSameTransactionAsLedger")
    void auditRowWrittenInSameTransactionAsLedger() throws Exception {
        mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "0.125", null, 0L))
                .andExpect(status().isOk());
        var audit = jdbc.queryForMap("""
                SELECT venue_id, actor_user_id, action, entity_type, entity_id, details::text AS details
                FROM audit_logs WHERE entity_type = 'INVENTORY_ITEM' AND entity_id = ?
                """, inventoryItemId);
        assertEquals(venueId, ((Number) audit.get("venue_id")).longValue());
        assertEquals(demoUserId("MANAGER"), ((Number) audit.get("actor_user_id")).longValue());
        assertEquals("INVENTORY_ADJUST", audit.get("action"));
        assertEquals("INVENTORY_ITEM", audit.get("entity_type"));
        assertEquals(inventoryItemId, ((Number) audit.get("entity_id")).longValue());
        String details = (String) audit.get("details");
        assertFalse(details.contains("\"reason\""));
        assertEquals(1, transactionCountForItem(inventoryItemId));

        // Prepare a valid MANAGER authentication for programmatic service call
        String token = bearer("MANAGER").replace("Bearer ", "");
        Jwt jwt = jwtDecoder.decode(token);
        var authentication = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
                inventory.adjust(inventoryItemId,
                        new InventoryService.AdjustmentRequest(
                                "PURCHASE_IN", new BigDecimal("1.000"), null, 1L),
                        demoUserId("MANAGER"), "127.0.0.1");
                throw new IllegalStateException("rollback check");
            }));
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertEquals(1, transactionCountForItem(inventoryItemId));
        assertEquals(1, auditCountForItem(inventoryItemId));
        assertEquals(new BigDecimal("0.125"), jdbc.queryForObject(
                "SELECT quantity FROM inventory_items WHERE id = ?", BigDecimal.class, inventoryItemId));
    }

    @Test
    @DisplayName("4C-43 transactionListDefaultLimitAndIdDesc")
    void transactionListDefaultLimitAndIdDesc() throws Exception {
        for (int i = 0; i < 3; i++) {
            long version = jdbc.queryForObject(
                    "SELECT version FROM inventory_items WHERE id = ?", Long.class, inventoryItemId);
            mvc.perform(adjustRequest("MANAGER", "PURCHASE_IN", "1", null, version))
                    .andExpect(status().isOk());
        }
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(
                        jdbc.queryForObject("SELECT max(id) FROM inventory_transactions WHERE inventory_item_id = ?",
                                Long.class, inventoryItemId)));
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions")
                        .param("limit", "1").header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions")
                        .param("limit", "501").header("Authorization", bearer("MANAGER")))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions")
                        .param("limit", "0").header("Authorization", bearer("MANAGER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4C-24 seededPieceBottleIngredientsHaveBarInventoryRows")
    void seededPieceBottleIngredientsHaveBarInventoryRows() {
        int missing = jdbc.queryForObject("""
                SELECT count(*)
                FROM ingredients i
                WHERE i.sku LIKE 'bottle-%' AND i.unit = 'PIECE' AND i.is_active
                  AND EXISTS (
                    SELECT 1 FROM stock_locations l
                    JOIN venues v ON v.id = l.venue_id
                    WHERE l.type = 'BAR' AND v.code LIKE 'NV-%'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM inventory_items ii
                    JOIN stock_locations l ON l.id = ii.location_id
                    WHERE ii.ingredient_id = i.id AND l.type = 'BAR'
                  )
                """, Integer.class);
        assertEquals(0, missing);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder provisionRequest(
            String role, long locationId, long ingredient, String reorderLevel) throws Exception {
        return MockMvcRequestBuilders.post(INVENTORY)
                .header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "locationId", locationId, "ingredientId", ingredient, "reorderLevel", reorderLevel)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adjustRequest(
            String role, String type, String delta, String reason, Long expectedVersion) throws Exception {
        return MockMvcRequestBuilders.post(INVENTORY + "/" + inventoryItemId + "/adjust")
                .header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(adjustJson(type, delta, reason, expectedVersion));
    }

    private int transactionCountForItem(long itemId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE inventory_item_id = ?", Integer.class, itemId);
    }

    private int transactionCountForIngredient(long ingredient) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM inventory_transactions t
                JOIN inventory_items i ON i.id = t.inventory_item_id
                WHERE i.ingredient_id = ?
                """, Integer.class, ingredient);
    }

    private int auditCountForItem(long itemId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE entity_type = 'INVENTORY_ITEM' AND entity_id = ?
                """, Integer.class, itemId);
    }
}
