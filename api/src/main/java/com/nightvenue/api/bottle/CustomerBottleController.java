package com.nightvenue.api.bottle;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/customers/me/bottles")
public class CustomerBottleController {
    private final BottleQueryService bottles;

    public CustomerBottleController(BottleQueryService bottles) {
        this.bottles = bottles;
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<BottleQueryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return bottles.customerBottles(jwt.getSubject(), status, page, size);
    }
}
