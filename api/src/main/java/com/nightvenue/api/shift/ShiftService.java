package com.nightvenue.api.shift;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class ShiftService {
    private final ShiftRepository shifts;
    private final OrderRepository orders;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final OrderAuditWriter audit;
    private final Clock clock;

    public ShiftService(ShiftRepository shifts, OrderRepository orders, VenueAccessService venues,
                        BusinessDateService businessDates, OrderAuditWriter audit, Clock clock) {
        this.shifts = shifts;
        this.orders = orders;
        this.venues = venues;
        this.businessDates = businessDates;
        this.audit = audit;
        this.clock = clock;
    }

    public Optional<CashierShift> findOpenShiftByCashier(long cashierId) {
        return shifts.findOpenShiftByCashier(cashierId);
    }

    @Transactional
    public CashierShift openShift(OpenShiftRequest request, String jwtSubject) {
        long cashierId = Long.parseLong(jwtSubject);
        Instant now = clock.instant();
        try {
            venues.requireAccess(request.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }

        OrderRepository.VenueContext requestedVenue = orders.findVenueContext(request.venueId())
                .orElseThrow(ResourceNotFound::new);
        LocalDate newBusinessDate = businessDates.businessDate(
                now, ZoneId.of(requestedVenue.timezone()), requestedVenue.businessDayCutoffHour());

        CashierShift existing = shifts.findOpenShiftByCashier(cashierId).orElse(null);
        if (existing != null) {
            // OD-2: compare the existing OPEN shift against the current business date of
            // its OWN venue; the V8 partial unique index is per cashier, not per venue.
            OrderRepository.VenueContext ownVenue = orders.findVenueContext(existing.venueId())
                    .orElseThrow(() -> new IllegalStateException("Venue context not found for open shift"));
            LocalDate ownCurrentBusinessDate = businessDates.businessDate(
                    now, ZoneId.of(ownVenue.timezone()), ownVenue.businessDayCutoffHour());
            if (existing.businessDate().compareTo(ownCurrentBusinessDate) >= 0) {
                throw new Conflict("SHIFT_ALREADY_OPEN");
            }
            int flipped = shifts.markStaleShiftForReview(
                    existing.id(), cashierId, ownCurrentBusinessDate, now);
            if (flipped == 1) {
                audit.shiftForcedReview(
                        existing.venueId(), cashierId, existing.id(), existing.businessDate(), now);
            }
            // flipped == 0 means a concurrent request already resolved this row;
            // the atomic insert below is the arbiter and never overwrites a newer state.
        }

        CashierShift created = shifts.insertOpenShiftIfAbsent(
                        request.venueId(), cashierId, newBusinessDate, request.openingFloat(), now)
                .orElseThrow(() -> new Conflict("SHIFT_ALREADY_OPEN"));
        audit.shiftOpened(request.venueId(), cashierId, created.id(), newBusinessDate, now);
        return created;
    }
}
