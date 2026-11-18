package com.nightvenue.api.shift;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import org.springframework.security.access.AccessDeniedException;
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

    @Transactional
    public SubmitShiftOutcome submitShift(long shiftId, SubmitShiftRequest request, String jwtSubject) {
        long actorId = Long.parseLong(jwtSubject);
        CashierShift found = shifts.findById(shiftId).orElseThrow(ResourceNotFound::new);
        try {
            venues.requireAccess(found.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }
        if (found.cashierId() != actorId) throw new AccessDeniedException("Only the shift owner may submit");

        CashierShift locked = shifts.lockById(shiftId).orElseThrow(ResourceNotFound::new);
        if (locked.venueId() != found.venueId()) throw new ResourceNotFound();
        try {
            venues.requireAccess(locked.venueId());
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }
        if (locked.cashierId() != actorId) throw new AccessDeniedException("Only the shift owner may submit");
        if (locked.status() != CashierShiftStatus.OPEN) throw new Conflict("SHIFT_NOT_OPEN");

        Instant now = clock.instant();
        OrderRepository.VenueContext venue = orders.findVenueContext(locked.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue context not found for shift"));
        LocalDate currentDate = businessDates.businessDate(now, ZoneId.of(venue.timezone()), venue.businessDayCutoffHour());
        if (locked.businessDate().isBefore(currentDate)) {
            int flipped = shifts.markStaleShiftForReview(locked.id(), actorId, currentDate, now);
            if (flipped != 1) throw new IllegalStateException("Expected to move one stale shift to review");
            audit.shiftForcedReview(locked.venueId(), actorId, locked.id(), locked.businessDate(), now);
            return new SubmitShiftOutcome(null, true);
        }

        try {
            long expectedCash = Math.addExact(locked.openingFloat(), shifts.sumPaidCash(locked.id()).longValueExact());
            long actualCash = request.actualCash();
            long variance = Math.subtractExact(actualCash, expectedCash);
            String note = request.note() == null || request.note().strip().isEmpty() ? null : request.note().strip();
            if (shifts.markSubmitted(locked.id(), expectedCash, actualCash, variance, note, now) != 1)
                throw new IllegalStateException("Expected to submit one open shift");
            audit.shiftSubmitted(locked.venueId(), actorId, locked.id(), expectedCash, actualCash, variance, now);
            return new SubmitShiftOutcome(new ShiftSubmissionView(locked.id(), CashierShiftStatus.SUBMITTED,
                    locked.openingFloat(), expectedCash, actualCash, variance, now, note), false);
        } catch (ArithmeticException e) {
            throw new BusinessRuleViolation("SHIFT_CASH_TOTAL_OVERFLOW");
        }
    }

    @Transactional
    public ShiftReviewView reviewShift(long shiftId, ReviewShiftRequest request, String jwtSubject) {
        long reviewerId = Long.parseLong(jwtSubject);
        CashierShift found = shifts.findById(shiftId).orElseThrow(ResourceNotFound::new);
        requireVenueAccess(found.venueId());
        if (found.cashierId() == reviewerId) throw new AccessDeniedException("A shift owner cannot review their shift");

        CashierShift locked = shifts.lockById(shiftId).orElseThrow(ResourceNotFound::new);
        if (locked.venueId() != found.venueId()) throw new ResourceNotFound();
        requireVenueAccess(locked.venueId());
        if (locked.cashierId() == reviewerId) throw new AccessDeniedException("A shift owner cannot review their shift");
        if (locked.status() != CashierShiftStatus.SUBMITTED && locked.status() != CashierShiftStatus.MANAGER_REVIEW)
            throw new Conflict("SHIFT_NOT_REVIEWABLE");

        String note = request.note() == null ? null : request.note().strip();
        if (note != null && note.isEmpty()) note = null;
        long expectedCash;
        long actualCash;
        long variance;
        CashierShiftStatus sourceStatus = locked.status();
        Instant reviewedAt = clock.instant();
        if (sourceStatus == CashierShiftStatus.SUBMITTED) {
            if (request.actualCash() != null) throw new BusinessRuleViolation("ACTUAL_CASH_NOT_ALLOWED");
            ShiftRepository.ReviewData values = shifts.reviewData(locked.id()).orElseThrow(ResourceNotFound::new);
            if (values.expectedCash() == null || values.actualCash() == null || values.variance() == null)
                throw new Conflict("SHIFT_NOT_REVIEWABLE");
            expectedCash = values.expectedCash();
            actualCash = values.actualCash();
            variance = values.variance();
            requireReviewNote(note, variance != 0 || locked.forcedReviewAt() != null);
            if (shifts.reconcileSubmitted(locked.id(), reviewerId, note, reviewedAt) != 1)
                throw new IllegalStateException("Expected to reconcile one submitted shift");
        } else {
            if (request.actualCash() == null) throw new BusinessRuleViolation("ACTUAL_CASH_REQUIRED");
            try {
                expectedCash = Math.addExact(locked.openingFloat(), shifts.sumPaidCash(locked.id()).longValueExact());
                actualCash = request.actualCash();
                variance = Math.subtractExact(actualCash, expectedCash);
            } catch (ArithmeticException e) {
                throw new BusinessRuleViolation("SHIFT_CASH_TOTAL_OVERFLOW");
            }
            requireReviewNote(note, variance != 0 || locked.forcedReviewAt() != null);
            if (shifts.reconcileManagerReview(locked.id(), reviewerId, expectedCash, actualCash, variance,
                    note, reviewedAt) != 1)
                throw new IllegalStateException("Expected to reconcile one manager-review shift");
        }

        audit.shiftReconciled(locked.venueId(), reviewerId, locked.id(), sourceStatus.name(),
                expectedCash, actualCash, variance, reviewedAt);
        return shifts.findReviewView(locked.id()).orElseThrow(ResourceNotFound::new);
    }

    private void requireVenueAccess(long venueId) {
        try {
            venues.requireAccess(venueId);
        } catch (VenueNotFound e) {
            throw new ResourceNotFound();
        }
    }

    private static void requireReviewNote(String note, boolean required) {
        if (required && (note == null || note.isBlank()))
            throw new BusinessRuleViolation("REVIEW_NOTE_REQUIRED");
    }

    public record SubmitShiftOutcome(ShiftSubmissionView view, boolean stale) {}
}
