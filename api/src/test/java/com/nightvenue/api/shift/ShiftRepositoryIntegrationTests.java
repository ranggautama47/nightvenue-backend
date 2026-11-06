package com.nightvenue.api.shift;

import com.nightvenue.api.payment.PaymentIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShiftRepositoryIntegrationTests extends PaymentIntegrationTestSupport {
    @Autowired PlatformTransactionManager transactionManager;

    @Test void findOpenShiftByCashier_returnsOnlyOpenShift() {
        jdbc.update("""
                INSERT INTO cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,version,created_at,updated_at)
                VALUES (?, ?, CURRENT_DATE, 'SUBMITTED', 0, now(), 0, now(), now())
                """, venueId, cashierId);
        CashierShift open = createShift(10);
        assertEquals(open.id(), shifts.findOpenShiftByCashier(cashierId).orElseThrow().id());
    }

    @Test void lockOpenShift_locksExpectedRow() throws Exception {
        CashierShift open = createShift(0);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assertEquals(open.id(), shifts.lockOpenShiftByCashier(cashierId).orElseThrow().id());
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                assertEquals(open.id(), shifts.lockOpenShiftByCashier(cashierId).orElseThrow().id());
            }));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "second FOR UPDATE must wait for the locked shift");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void partialUnique_allowsOnlyOneOpenShiftPerCashier() {
        createShift(0);
        assertThrows(DataIntegrityViolationException.class, () -> createShift(20));
    }

    @Test void shiftForeignKeys_restrictReferencedDeletion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long extraVenue = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, "FK-" + suffix, "FK " + suffix);
        long extraCashier = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'FK User', 'CASHIER', true, 0, now(), now()) RETURNING id
                """, Long.class, suffix + "@fk.test");
        try {
            jdbc.update("""
                    INSERT INTO cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,version,created_at,updated_at)
                    VALUES (?, ?, CURRENT_DATE, 'OPEN', 0, now(), 0, now(), now())
                    """, extraVenue, extraCashier);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM users WHERE id = ?", extraCashier));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM venues WHERE id = ?", extraVenue));
        } finally {
            jdbc.update("DELETE FROM cashier_shifts WHERE venue_id = ?", extraVenue);
            jdbc.update("DELETE FROM users WHERE id = ?", extraCashier);
            jdbc.update("DELETE FROM venues WHERE id = ?", extraVenue);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for transaction release", e);
        }
    }
}
