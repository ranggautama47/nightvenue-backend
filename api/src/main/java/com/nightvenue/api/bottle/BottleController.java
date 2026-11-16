package com.nightvenue.api.bottle;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bottles")
public class BottleController {

    private final BottleService bottleService;
    private final BottleQueryService bottleQueryService;

    public BottleController(BottleService bottleService, BottleQueryService bottleQueryService) {
        this.bottleService = bottleService;
        this.bottleQueryService = bottleQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public List<BottleQueryResponse> list(
            @RequestParam long venueId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return bottleQueryService.staffBottles(venueId, status, customerId, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BottleResponse deposit(
            @Valid @RequestBody DepositBottleRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return bottleService.deposit(request, idempotencyKey, jwt.getSubject());
    }

    @PostMapping("/redeem")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BottleRedeemResponse redeem(
            @Valid @RequestBody RedeemBottleRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return bottleService.redeem(request, idempotencyKey, jwt.getSubject());
    }
}

