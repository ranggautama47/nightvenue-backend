package com.nightvenue.api.shift;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shifts")
public class ShiftController {
    private final ShiftService shifts;

    public ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CashierShift openShift(
            @Valid @RequestBody OpenShiftRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return shifts.openShift(request, jwt.getSubject());
    }
}
