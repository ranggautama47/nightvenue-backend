package com.nightvenue.api.bottle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class BottleVoidGuardTests extends BottleRedeemTestSupport {

    @ParameterizedTest
    @CsvSource({"ORDERED,RESTORED", "ORDERED,WASTED", "SERVED,RESTORED", "SERVED,WASTED"})
    void voidWithBottleRedemptionReturns409AndRollsBack(String status, String disposition) throws Exception {

        Timestamp now = Timestamp.from(Instant.now());
        // Add a PRODUCT item with the given status
        jdbc.update(
                "INSERT INTO order_items(order_id,product_id,item_type,product_name_snapshot,unit_price_snapshot,quantity,line_total,status,created_at,updated_at) VALUES (?,?,'PRODUCT','Gin',1000,1,1000,?,?,?)",
                order, product, status, now, now);
        // Add a BOTTLE_REDEMPTION item
        jdbc.update(
                "INSERT INTO order_items(order_id,product_id,item_type,product_name_snapshot,unit_price_snapshot,quantity,line_total,status,created_at,updated_at) VALUES (?,?,'BOTTLE_REDEMPTION','Gin',0,1,0,'SERVED',?,?)",
                order, product, now, now);

        int inventoryBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Integer.class);
        int remainingBefore = jdbc.queryForObject("SELECT remaining_volume_ml FROM bottle_vaults WHERE id=?", Integer.class, vault);

        UUID key = UUID.randomUUID();
        String body = "{\"reason\":\"guard test\",\"disposition\":\"" + disposition + "\"}";
        mvc.perform(post("/api/v1/orders/" + order + "/void")
                        .header("Authorization", bearer(user, "MANAGER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        // Order status still OPEN
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order)).isEqualTo("OPEN");
        // Vault remaining unchanged
        assertThat(jdbc.queryForObject("SELECT remaining_volume_ml FROM bottle_vaults WHERE id=?", Integer.class, vault)).isEqualTo(remainingBefore);
        // No void_logs row
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM void_logs WHERE order_id=?", Integer.class, order)).isZero();
        // No inventory change
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Integer.class)).isEqualTo(inventoryBefore);
        // Idempotency key not left reserved (no row for that key)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key=?", Integer.class, key)).isZero();
    }
}