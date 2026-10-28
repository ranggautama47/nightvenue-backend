package com.nightvenue.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/test")
class TestAuthorizationController {
    private static final Logger log = LoggerFactory.getLogger(TestAuthorizationController.class);

    private final VenueAccessService venues;

    TestAuthorizationController(VenueAccessService venues) {
        this.venues = venues;
    }

    @GetMapping("/roles/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> adminOnly() {
        return Map.of("role", "ADMIN");
    }

    @GetMapping("/roles/manager-only")
    @PreAuthorize("hasRole('MANAGER')")
    Map<String, String> managerOnly() {
        return Map.of("role", "MANAGER");
    }

    @GetMapping("/roles/cashier-only")
    @PreAuthorize("hasRole('CASHIER')")
    Map<String, String> cashierOnly() {
        return Map.of("role", "CASHIER");
    }

    @GetMapping("/roles/customer-only")
    @PreAuthorize("hasRole('CUSTOMER')")
    Map<String, String> customerOnly() {
        return Map.of("role", "CUSTOMER");
    }

    @GetMapping("/venues/{venueId}/access")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    Map<String, Long> venueAccess(@PathVariable long venueId) {
        venues.requireAccess(venueId);
        return Map.of("venueId", venueId);
    }

    /**
     * Test-only failure path that records an exception (with stack trace) at DEBUG level
     * in the application package, so the log-redaction test can prove secrets are absent
     * from throwables as well as formatted messages.
     */
    @GetMapping("/logging/exception")
    Map<String, String> loggedException() {
        log.debug("Simulated application failure for log-redaction test",
                new IllegalStateException("simulated failure"));
        return Map.of("ok", "true");
    }
}
