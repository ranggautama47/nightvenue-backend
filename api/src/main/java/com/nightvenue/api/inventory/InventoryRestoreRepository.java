package com.nightvenue.api.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InventoryRestoreRepository {
    private final JdbcTemplate jdbc;

    public InventoryRestoreRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SaleDeduction> findSaleDeductions(List<Long> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        return jdbc.query("""
                SELECT t.id, t.reference_id AS order_item_id, t.inventory_item_id, t.delta
                FROM inventory_transactions t
                WHERE t.type = 'SALE_DEDUCT' AND t.reference_type = 'ORDER_ITEM'
                  AND t.reference_id IN (%s)
                ORDER BY t.inventory_item_id ASC, t.reference_id ASC, t.id ASC
                """.formatted(placeholders), (rs, row) -> new SaleDeduction(rs.getLong("id"), rs.getLong("order_item_id"),
                rs.getLong("inventory_item_id"), rs.getBigDecimal("delta")),
                itemIds.toArray());
    }

    public record SaleDeduction(long transactionId, long orderItemId, long inventoryItemId,
                                java.math.BigDecimal delta) { }
}
