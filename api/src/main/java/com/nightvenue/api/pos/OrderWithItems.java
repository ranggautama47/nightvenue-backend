package com.nightvenue.api.pos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderWithItems(
        long id,
        long venueId,
        String orderNumber,
        Long tableId,
        Long customerId,
        long cashierId,
        LocalDate businessDate,
        String status,
        long subtotal,
        long discountAmount,
        long serviceChargeAmount,
        long taxAmount,
        long totalDue,
        long paidTotal,
        long minimumSpendSnapshot,
        Instant openedAt,
        Instant closedAt,
        long version,
        List<OrderRepository.OrderItem> items
) {
    public static OrderWithItems of(OrderRepository.Order order, List<OrderRepository.OrderItem> items) {
        return new OrderWithItems(
                order.id(),
                order.venueId(),
                order.orderNumber(),
                order.tableId(),
                order.customerId(),
                order.cashierId(),
                order.businessDate(),
                order.status(),
                order.subtotal(),
                order.discountAmount(),
                order.serviceChargeAmount(),
                order.taxAmount(),
                order.totalDue(),
                order.paidTotal(),
                order.minimumSpendSnapshot(),
                order.openedAt(),
                order.closedAt(),
                order.version(),
                items
        );
    }
}

