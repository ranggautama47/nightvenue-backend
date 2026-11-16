package com.nightvenue.api.bottle;

import com.nightvenue.api.businessday.BusinessDateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class BottleExpiryService {
    private static final Logger log = LoggerFactory.getLogger(BottleExpiryService.class);

    private final BottleRepository bottles;
    private final BottleAuditWriter audits;
    private final BusinessDateService businessDates;
    private final TransactionTemplate venueTransaction;

    public BottleExpiryService(
            BottleRepository bottles,
            BottleAuditWriter audits,
            BusinessDateService businessDates,
            PlatformTransactionManager transactionManager) {
        this.bottles = bottles;
        this.audits = audits;
        this.businessDates = businessDates;
        this.venueTransaction = new TransactionTemplate(transactionManager);
        this.venueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int expireDue(Instant now) {
        int expiredCount = 0;
        for (BottleRepository.VenueExpiryContext venue : bottles.findActiveVenuesForExpiry()) {
            try {
                Integer count = venueTransaction.execute(status -> expireVenue(venue, now));
                expiredCount += count == null ? 0 : count;
            } catch (RuntimeException exception) {
                log.warn("Bottle expiry failed venue_id={} error={}",
                        venue.id(), exception.getClass().getSimpleName());
            }
        }
        return expiredCount;
    }

    private int expireVenue(BottleRepository.VenueExpiryContext venue, Instant now) {
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(venue.timezone()), venue.businessDayCutoffHour());
        List<BottleRepository.ExpiredVault> expired =
                bottles.expireActiveVaults(venue.id(), businessDate, now);
        for (BottleRepository.ExpiredVault vault : expired) {
            bottles.insertExpiryTransaction(vault.id(), vault.remainingVolumeMl(), now);
            audits.bottleExpired(venue.id(), vault.id(), vault.remainingVolumeMl(), now);
        }
        return expired.size();
    }
}
