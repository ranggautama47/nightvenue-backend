package com.nightvenue.api.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderRepository.Order open(
            @Valid @RequestBody OrderService.CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return orders.open(request, idempotencyKey, jwt.getSubject());
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderWithItems addItems(
            @PathVariable @Positive long id,
            @Valid @RequestBody AddOrderItemsRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return orders.addItems(id, request, idempotencyKey, jwt.getSubject());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public OrderRepository.Order get(@PathVariable @Positive long id) {
        return orders.get(id);
    }
}
