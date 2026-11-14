package com.nightvenue.api.payment;

import com.nightvenue.api.pos.OrderRepository;

public record PaymentWithOrder(Payment payment, OrderRepository.Order order) {
}
