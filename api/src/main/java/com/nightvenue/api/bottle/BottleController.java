package com.nightvenue.api.bottle;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/bottles")
public class BottleController {

    private final BottleService bottleService;

    public BottleController(BottleService bottleService) {
        this.bottleService = bottleService;
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
}

