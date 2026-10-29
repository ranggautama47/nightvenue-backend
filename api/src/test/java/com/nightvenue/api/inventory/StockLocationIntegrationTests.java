package com.nightvenue.api.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockLocationIntegrationTests extends InventoryIntegrationTestSupport {
    private static final String LOCATIONS = "/api/v1/stock-locations";
    private static final String INVENTORY = "/api/v1/inventory";

    @ParameterizedTest(name = "4C-01 {0}, role={1}")
    @MethodSource("accessMatrix")
    void everyEndpointEnforcesTheRoleMatrix(String endpoint, String role, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "GET locations" -> MockMvcRequestBuilders.get(LOCATIONS).param("venueId", Long.toString(venueId));
            case "GET location" -> MockMvcRequestBuilders.get(LOCATIONS + "/" + barLocationId);
            case "POST location" -> MockMvcRequestBuilders.post(LOCATIONS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "venueId", venueId, "name", fixture + "-matrix-" + UUID.randomUUID(), "type", "WAREHOUSE")));
            case "PUT location" -> MockMvcRequestBuilders.put(LOCATIONS + "/" + warehouseLocationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "venueId", venueId, "name", fixture + "-rename-" + UUID.randomUUID(),
                            "type", "WAREHOUSE")));
            case "POST inventory" -> {
                // Keep SKU <= 40 chars: fixture (e.g., N4C-<12>) + "-m-" + 8-char suffix = ~29 chars
                String shortSuffix = UUID.randomUUID().toString().substring(0, 8);
                long ingredient = createIngredient(fixture + "-m-" + shortSuffix, true);
                yield MockMvcRequestBuilders.post(INVENTORY).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locationId", barLocationId, "ingredientId", ingredient, "reorderLevel", "1.000")));
            }
            case "GET inventory" -> MockMvcRequestBuilders.get(INVENTORY)
                    .param("venueId", Long.toString(venueId));
            case "GET inventory item" -> MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId);
            case "GET transactions" -> MockMvcRequestBuilders.get(INVENTORY + "/" + inventoryItemId + "/transactions");
            case "POST adjust" -> {
                long version = jdbc.queryForObject(
                        "SELECT version FROM inventory_items WHERE id = ?", Long.class, inventoryItemId);
                yield MockMvcRequestBuilders.post(INVENTORY + "/" + inventoryItemId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON).content(adjustJson("PURCHASE_IN", "1", null, version));
            }
            default -> throw new IllegalArgumentException("Unknown endpoint");
        };
        if (!"ANONYMOUS".equals(role)) {
            request.header("Authorization", bearer(role));
        }
        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> accessMatrix() {
        List<String> endpoints = List.of(
                "GET locations", "GET location", "POST location", "PUT location",
                "POST inventory", "GET inventory", "GET inventory item", "GET transactions", "POST adjust");
        List<String> roles = List.of("ADMIN", "MANAGER", "CASHIER", "CUSTOMER", "ANONYMOUS");
        return endpoints.stream().flatMap(endpoint -> roles.stream().map(role -> Arguments.of(
                endpoint, role, expectedStatus(endpoint, role))));
    }

    private static int expectedStatus(String endpoint, String role) {
        if ("ANONYMOUS".equals(role)) return 401;
        if ("CUSTOMER".equals(role)) return 403;
        if ("CASHIER".equals(role)) {
            return endpoint.startsWith("GET ") && !endpoint.equals("GET transactions") ? 200 : 403;
        }
        return switch (endpoint) {
            case "GET locations", "GET location", "GET inventory", "GET inventory item",
                    "GET transactions" -> 200;
            case "POST location", "POST inventory" ->
                    "ADMIN".equals(role) || "MANAGER".equals(role) ? 201 : 403;
            case "PUT location", "POST adjust" ->
                    "ADMIN".equals(role) || "MANAGER".equals(role) ? 200 : 403;
            default -> throw new IllegalArgumentException("Unknown endpoint");
        };
    }

    @Test
    @DisplayName("4C-06 createWarehouseAndBarRoundTrip")
    void createWarehouseAndBarRoundTrip() throws Exception {
        jdbc.update("DELETE FROM stock_locations WHERE id = ?", otherBarLocationId);
        var warehouse = mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-roundtrip-warehouse",
                                "type", "WAREHOUSE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueId").value(otherVenueId))
                .andReturn();
        long newWarehouse = objectMapper.readTree(warehouse.getResponse().getContentAsString()).path("id").asLong();
        var bar = mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-roundtrip-bar", "type", "BAR"))))
                .andExpect(status().isCreated())
                .andReturn();
        long newBar = objectMapper.readTree(bar.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS + "/" + newBar)
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BAR"));
        mvc.perform(MockMvcRequestBuilders.get(LOCATIONS).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + newWarehouse + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + newBar + ")]").exists());
    }

    @Test
    @DisplayName("4C-07 secondBarSameVenue409Sanitized")
    void secondBarSameVenue409Sanitized() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-second-bar", "type", "BAR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Stock location already exists"))
                .andReturn();
        assertSanitized(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("4C-08 duplicateLocationName409Sanitized")
    void duplicateLocationName409Sanitized() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-warehouse", "type", "WAREHOUSE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Stock location already exists"))
                .andReturn();
        assertSanitized(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("4C-11 postgresEnforcesOneBarPerVenueAndNameUniqueness")
    void postgresEnforcesOneBarPerVenueAndNameUniqueness() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, 'BAR')
                """, venueId, fixture + "-direct-second-bar"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, 'WAREHOUSE')
                """, venueId, fixture + "-warehouse"));
    }

    @Test
    @DisplayName("4C-27 putCannotMoveVenue422")
    void putCannotMoveVenue422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(LOCATIONS + "/" + warehouseLocationId)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-moved", "type", "WAREHOUSE"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot move stock location to another venue"));
    }

    @Test
    @DisplayName("4C-28 putTypeToBarWhenBarExists409")
    void putTypeToBarWhenBarExists409() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(LOCATIONS + "/" + warehouseLocationId)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-warehouse", "type", "BAR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Stock location already exists"));
    }

    @Test
    @DisplayName("4C-29 putTypeChangeRejectedWhenInventoryExists422")
    void putTypeChangeRejectedWhenInventoryExists422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(LOCATIONS + "/" + barLocationId)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", venueId, "name", fixture + "-bar", "type", "WAREHOUSE"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot change stock location type with inventory"));
    }

    @Test
    @DisplayName("4C-46 adminPostLocationMissingVenue422")
    void adminPostLocationMissingVenue422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", Long.MAX_VALUE, "name", fixture + "-missing-venue", "type", "WAREHOUSE"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Venue does not exist or is inactive"));

        jdbc.update("UPDATE venues SET is_active = false WHERE id = ?", otherVenueId);
        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-inactive-venue", "type", "WAREHOUSE"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Venue does not exist or is inactive"));
    }

    @Test
    @DisplayName("4C-47 managerPostLocationUnassignedVenue404")
    void managerPostLocationUnassignedVenue404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", otherVenueId, "name", fixture + "-out-of-scope", "type", "WAREHOUSE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Venue not found"));

        mvc.perform(MockMvcRequestBuilders.post(LOCATIONS)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "venueId", Long.MAX_VALUE, "name", fixture + "-unknown", "type", "WAREHOUSE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Venue not found"));
    }

    private static void assertSanitized(String body) {
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("uq_stock_locations"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("SQL"));
    }
}
