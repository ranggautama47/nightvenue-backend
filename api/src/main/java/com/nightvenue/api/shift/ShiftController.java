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
import org.springframework.web.bind.annotation.PathVariable;
import com.nightvenue.api.common.Conflict;

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

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ShiftSubmissionView submitShift(@PathVariable long id, @Valid @RequestBody SubmitShiftRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        ShiftService.SubmitShiftOutcome outcome = shifts.submitShift(id, request, jwt.getSubject());
        if (outcome.stale()) throw new Conflict("SHIFT_NOT_OPEN");
        return outcome.view();
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ShiftReviewView reviewShift(@PathVariable long id, @Valid @RequestBody ReviewShiftRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return shifts.reviewShift(id, request, jwt.getSubject());
    }
}
