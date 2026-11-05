package com.nightvenue.api.pos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record VoidedOrderWithItems(
        long id, long venueId, String orderNumber, Long tableId, Long customerId, long cashierId,
        LocalDate businessDate, String status, long subtotal, long discountAmount,
        long serviceChargeAmount, long taxAmount, long totalDue, long paidTotal,
        long minimumSpendSnapshot, Instant openedAt, Instant closedAt, long version,
        List<VoidedItem> items) {

    public static VoidedOrderWithItems of(OrderRepository.Order order,
                                           List<OrderRepository.OrderItem> items,
                                           java.util.Map<Long, String> dispositions) {
        return new VoidedOrderWithItems(order.id(), order.venueId(), order.orderNumber(), order.tableId(),
                order.customerId(), order.cashierId(), order.businessDate(), order.status(), order.subtotal(),
                order.discountAmount(), order.serviceChargeAmount(), order.taxAmount(), order.totalDue(),
                order.paidTotal(), order.minimumSpendSnapshot(), order.openedAt(), order.closedAt(), order.version(),
                items.stream().map(item -> new VoidedItem(item.id(), item.orderId(), item.productId(), item.itemType(),
                        item.productNameSnapshot(), item.unitPriceSnapshot(), item.quantity(), item.lineTotal(),
                        item.status(), item.createdAt(), item.updatedAt(), dispositions.get(item.id()))).toList());
    }

    public record VoidedItem(long id, long orderId, long productId, String itemType, String productNameSnapshot,
                             long unitPriceSnapshot, int quantity, long lineTotal, String status,
                             Instant createdAt, Instant updatedAt, String disposition) { }
}
