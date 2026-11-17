package com.nightvenue.api.shift;

import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Scheduled cutoff job service that moves stale OPEN cashier shifts to MANAGER_REVIEW (FR-SHF-05, ADR-002).
 * <p>
 * Disjointness rationale: PaymentService's CASH path validates that a cashier's OPEN shift matches both the
 * venue ID and the current business date (businessDate.equals(shift.businessDate())), rejecting shifts with
 * {@code business_date < currentDate} with 409 SHIFT_NOT_OPEN. Consequently, the cutoff job only operates on
 * shifts whose business date has already passed, avoiding competition with active CASH payment creation.
 * Furthermore, row-level locks and conditional CAS updates ensure safe execution under concurrency.
 */
@Service
public class ShiftCutoffService {
    private static final Logger log = LoggerFactory.getLogger(ShiftCutoffService.class);

    private final ShiftRepository shifts;
    private final OrderRepository orders;
    private final OrderAuditWriter audits;
    private final BusinessDateService businessDates;
    private final Clock clock;
    private final TransactionTemplate venueTransaction;

    public ShiftCutoffService(
            ShiftRepository shifts,
            OrderRepository orders,
            OrderAuditWriter audits,
            BusinessDateService businessDates,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.shifts = shifts;
        this.orders = orders;
        this.audits = audits;
        this.businessDates = businessDates;
        this.clock = clock;
        this.venueTransaction = new TransactionTemplate(transactionManager);
        this.venueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int runCutoff() {
        Instant now = clock.instant();
        int flippedCount = 0;
        List<Long> venueIds = shifts.findVenuesWithOpenShifts();
        for (Long venueId : venueIds) {
            try {
                Integer count = venueTransaction.execute(status -> cutoffVenue(venueId, now));
                flippedCount += (count == null ? 0 : count);
            } catch (RuntimeException exception) {
                log.warn("Shift cutoff failed venue_id={} error={}",
                        venueId, exception.getClass().getSimpleName());
            }
        }
        return flippedCount;
    }

    private int cutoffVenue(long venueId, Instant now) {
        OrderRepository.VenueContext venue = orders.findVenueContext(venueId)
                .orElse(null);
        if (venue == null) {
            return 0;
        }

        LocalDate currentDate = businessDates.businessDate(
                now, ZoneId.of(venue.timezone()), venue.businessDayCutoffHour());

        List<ShiftRepository.StaleShiftCandidate> staleShifts =
                shifts.findStaleOpenShifts(venueId, currentDate);

        int venueFlipped = 0;
        for (ShiftRepository.StaleShiftCandidate candidate : staleShifts) {
            int updated = shifts.markStaleShiftForReviewById(candidate.id(), currentDate, now);
            if (updated == 1) {
                audits.shiftForcedReviewBySystem(venueId, candidate.id(), candidate.businessDate(), now);
                venueFlipped++;
            }
        }
        return venueFlipped;
    }
}
