package com.nightvenue.api.bottle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BottleExpiryIntegrationTests extends BottleExpiryTestSupport {
    @Test
    void expiresOnlyActiveWithPastExpiry() throws Exception {
        Instant now = clock.instant();
        LocalDate businessDate = businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6);
        long active = insertVault("ACTIVE", businessDate.minusDays(1));
        long empty = insertVault("EMPTY", businessDate.minusDays(1));
        long released = insertVault("RELEASED", businessDate.minusDays(1));
        long alreadyExpired = insertVault("EXPIRED", businessDate.minusDays(2));
        long future = insertVault("ACTIVE", businessDate.plusDays(1));

        assertThat(expiry.expireDue(now)).isEqualTo(1);
        assertThat(vaultStatus(active)).isEqualTo("EXPIRED");
        assertThat(vaultStatus(empty)).isEqualTo("EMPTY");
        assertThat(vaultStatus(released)).isEqualTo("RELEASED");
        assertThat(vaultStatus(alreadyExpired)).isEqualTo("EXPIRED");
        assertThat(vaultStatus(future)).isEqualTo("ACTIVE");
        assertThat(transactionCount(active, "EXPIRE")).isEqualTo(1);
        assertThat(transactionCount(empty, "EXPIRE")).isZero();
        assertThat(transactionCount(released, "EXPIRE")).isZero();
        assertThat(transactionCount(alreadyExpired, "EXPIRE")).isZero();
        assertThat(transactionCount(future, "EXPIRE")).isZero();
    }

    @Test
    void expiryDateEqualToBusinessDateNotExpired() throws Exception {
        Instant now = clock.instant();
        LocalDate businessDate = businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6);
        long vault = insertVault("ACTIVE", businessDate);

        assertThat(expiry.expireDue(now)).isZero();
        assertThat(vaultStatus(vault)).isEqualTo("ACTIVE");
        assertThat(transactionCount(vault, "EXPIRE")).isZero();
    }

    @Test
    void expireTransactionShape() throws Exception {
        Instant now = clock.instant();
        LocalDate dueDate = businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6).minusDays(1);
        long vault = insertVault("ACTIVE", dueDate, 317);

        assertThat(expiry.expireDue(now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM bottle_vault_transactions WHERE vault_id=?",
                String.class, vault)).isEqualTo("EXPIRE");
        assertThat(jdbc.queryForObject("SELECT volume_delta_ml FROM bottle_vault_transactions WHERE vault_id=?",
                Integer.class, vault)).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_after_ml FROM bottle_vault_transactions WHERE vault_id=?",
                Integer.class, vault)).isEqualTo(317);
        assertThat(jdbc.queryForObject("SELECT performed_by IS NULL FROM bottle_vault_transactions WHERE vault_id=?",
                Boolean.class, vault)).isTrue();
        assertThat(jdbc.queryForObject("SELECT owner_confirmed FROM bottle_vault_transactions WHERE vault_id=?",
                Boolean.class, vault)).isFalse();
        assertThat(jdbc.queryForObject("SELECT order_item_id IS NULL FROM bottle_vault_transactions WHERE vault_id=?",
                Boolean.class, vault)).isTrue();
        assertThat(jdbc.queryForObject("SELECT note FROM bottle_vault_transactions WHERE vault_id=?",
                String.class, vault)).contains("expir");
        assertThat(remainingVolume(vault)).isEqualTo(317);
    }

    @Test
    void auditWrittenWithNullActor() throws Exception {
        Instant now = clock.instant();
        long vault = insertVault("ACTIVE",
                businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6).minusDays(1));

        expiry.expireDue(now);

        assertThat(jdbc.queryForObject("""
                SELECT actor_user_id IS NULL AND action = 'BOTTLE_EXPIRE'
                    AND entity_type = 'BOTTLE_VAULT'
                FROM audit_logs WHERE venue_id = ? AND entity_id = ?
                """, Boolean.class, venueA, vault)).isTrue();
    }

    @Test
    void rerunIsIdempotent() throws Exception {
        Instant now = clock.instant();
        long vault = insertVault("ACTIVE",
                businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6).minusDays(1));

        assertThat(expiry.expireDue(now)).isEqualTo(1);
        int transactionCount = transactionCount(vault, "EXPIRE");
        int auditCount = auditCount(vault);
        assertThat(expiry.expireDue(now)).isZero();
        assertThat(transactionCount(vault, "EXPIRE")).isEqualTo(transactionCount);
        assertThat(auditCount(vault)).isEqualTo(auditCount);
    }

    @Test
    void perVenueBusinessDate() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        jdbc.update("UPDATE venues SET timezone='Pacific/Kiritimati', business_day_cutoff_hour=6 WHERE id=?", venueA);
        jdbc.update("UPDATE venues SET timezone='Pacific/Honolulu', business_day_cutoff_hour=6 WHERE id=?", venueB);
        LocalDate expiryDate = LocalDate.of(2025, 12, 31);
        LocalDate venueABusinessDate = businessDates.businessDate(now, ZoneId.of("Pacific/Kiritimati"), 6);
        LocalDate venueBBusinessDate = businessDates.businessDate(now, ZoneId.of("Pacific/Honolulu"), 6);
        assertThat(venueABusinessDate).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(venueBBusinessDate).isEqualTo(expiryDate);
        long dueAtA = insertVaultWithToken(venueA, customerId, productA, "ACTIVE", expiryDate, 750).id();
        long dueAtB = insertVaultWithToken(venueB, customerId, productB, "ACTIVE", expiryDate, 750).id();

        assertThat(expiry.expireDue(now)).isEqualTo(1);
        assertThat(vaultStatus(dueAtA)).isEqualTo("EXPIRED");
        assertThat(vaultStatus(dueAtB)).isEqualTo("ACTIVE");
    }

    @Test
    void failedVenueDoesNotStopOthers() throws Exception {
        Instant now = clock.instant();
        LocalDate venueBBusinessDate = businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6);
        long badVenueVault = insertVault("ACTIVE", venueBBusinessDate.minusDays(1));
        long goodVenueVault = insertVaultWithToken(venueB, customerId, productB, "ACTIVE",
                venueBBusinessDate.minusDays(1), 750).id();
        jdbc.update("UPDATE venues SET timezone='Not/AZone' WHERE id=?", venueA);

        assertThat(expiry.expireDue(now)).isEqualTo(1);
        assertThat(vaultStatus(badVenueVault)).isEqualTo("ACTIVE");
        assertThat(vaultStatus(goodVenueVault)).isEqualTo("EXPIRED");
    }

    @Test
    void expiryVsRedeemRace() throws Exception {
        Instant now = clock.instant();
        LocalDate dueDate = businessDates.businessDate(now, ZoneId.of("Asia/Jakarta"), 6).minusDays(1);
        VaultFixture vault = insertVaultWithToken("ACTIVE", dueDate, 487);
        insertOrder();
        CyclicBarrier start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var expire = pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return expiry.expireDue(now);
            });
            var redeem = pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return mvc.perform(redeemRequest(vault.token()))
                        .andReturn().getResponse().getStatus();
            });
            int expireCount = expire.get(30, TimeUnit.SECONDS);
            int redeemStatus = redeem.get(30, TimeUnit.SECONDS);
            assertThat(expireCount).isEqualTo(1);
            assertThat(redeemStatus).isEqualTo(422);
        } finally {
            pool.shutdownNow();
        }
        assertThat(vaultStatus(vault.id())).isEqualTo("EXPIRED");
        assertThat(remainingVolume(vault.id())).isEqualTo(487);
        assertThat(remainingVolume(vault.id())).isGreaterThanOrEqualTo(0);
        assertThat(transactionCount(vault.id(), "EXPIRE")).isEqualTo(1);
        assertThat(transactionCount(vault.id(), "REDEEM")).isZero();
    }

    private int auditCount(long vaultId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='BOTTLE_EXPIRE'",
                Integer.class, vaultId);
    }
}
