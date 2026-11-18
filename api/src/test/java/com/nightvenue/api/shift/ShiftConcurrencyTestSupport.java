package com.nightvenue.api.shift;

import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class ShiftConcurrencyTestSupport {

    public record Outcome(int status, String body, int count) {}

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected ShiftRepository shiftRepository;
    @Autowired protected BusinessDateService businessDates;
    @Autowired protected ShiftCutoffService shiftCutoffService;

    protected String pfx;
    protected long venueAId;
    protected long venueBId;
    protected long cashierA1;
    protected long cashierA2;
    protected long cashierA3;
    protected long managerA1;
    protected long managerA2;
    protected long adminA;
    protected long managerB;

    @BeforeEach
    void setupConcurrencyFixtures() {
        pfx = "cx" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        venueAId = insertVenue(pfx + "A");
        venueBId = insertVenue(pfx + "B");

        cashierA1 = insertUser(pfx + "-c1", "CASHIER");
        cashierA2 = insertUser(pfx + "-c2", "CASHIER");
        cashierA3 = insertUser(pfx + "-c3", "CASHIER");
        managerA1 = insertUser(pfx + "-m1", "MANAGER");
        managerA2 = insertUser(pfx + "-m2", "MANAGER");
        adminA    = insertUser(pfx + "-adm", "ADMIN");
        managerB  = insertUser(pfx + "-bm", "MANAGER");

        insertUserVenue(cashierA1, venueAId);
        insertUserVenue(cashierA2, venueAId);
        insertUserVenue(cashierA3, venueAId);
        insertUserVenue(managerA1, venueAId);
        insertUserVenue(managerA2, venueAId);
        insertUserVenue(adminA, venueAId);

        // B-only manager: attached only to venue B
        insertUserVenue(managerB, venueBId);
    }

    @AfterEach
    void cleanupConcurrencyFixtures() {
        if (pfx == null) {
            return;
        }
        // Scoped cleanup by fixture users and fixture venues in strict FK dependency order
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id IN (?, ?, ?, ?, ?, ?, ?)",
                cashierA1, cashierA2, cashierA3, managerA1, managerA2, adminA, managerB);
        jdbc.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE venue_id IN (?, ?))",
                venueAId, venueBId);
        jdbc.update("DELETE FROM payments WHERE venue_id IN (?, ?) OR created_by IN (?, ?, ?, ?, ?, ?, ?)",
                venueAId, venueBId, cashierA1, cashierA2, cashierA3, managerA1, managerA2, adminA, managerB);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id IN (?, ?))",
                venueAId, venueBId);
        jdbc.update("DELETE FROM orders WHERE venue_id IN (?, ?)",
                venueAId, venueBId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id IN (?, ?)",
                venueAId, venueBId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?) OR actor_user_id IN (?, ?, ?, ?, ?, ?, ?)",
                venueAId, venueBId, cashierA1, cashierA2, cashierA3, managerA1, managerA2, adminA, managerB);
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (?, ?) OR user_id IN (?, ?, ?, ?, ?, ?, ?)",
                venueAId, venueBId, cashierA1, cashierA2, cashierA3, managerA1, managerA2, adminA, managerB);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?, ?, ?, ?)",
                cashierA1, cashierA2, cashierA3, managerA1, managerA2, adminA, managerB);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)",
                venueAId, venueBId);
    }

    protected long insertVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    protected long insertUser(String nameSuffix, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, (nameSuffix + "@test.local").toLowerCase(), nameSuffix, role);
    }

    protected void insertUserVenue(long userId, long venueId) {
        jdbc.update("INSERT INTO user_venues(user_id, venue_id) VALUES (?, ?)", userId, venueId);
    }

    protected String bearer(long id, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    protected long insertSubmittedShift(long venueId, long owner, LocalDate bDate,
                                        long openingFloat, long expected, long actual, long variance, String note) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,
                    created_at,updated_at,submitted_at,expected_cash,actual_cash,variance,submit_note)
                VALUES (?, ?, ?, 'SUBMITTED', ?, ?, 0, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, venueId, owner, java.sql.Date.valueOf(bDate), openingFloat, now,
                now, now, now, expected, actual, variance, note);
    }

    protected long insertOrder(long venueId, long cashierId, LocalDate bDate, long totalDue) {
        Timestamp now = Timestamp.from(Instant.now());
        String orderNum = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id, order_number, cashier_id, business_date, status, subtotal, total_due,
                                    opened_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, orderNum, cashierId, java.sql.Date.valueOf(bDate), totalDue, totalDue, now, now, now);
    }

    protected long getShiftVersion(long shiftId) {
        return jdbc.queryForObject("SELECT version FROM cashier_shifts WHERE id = ?", Long.class, shiftId);
    }

    /**
     * Latches synchronize release across worker threads but do not guarantee DB-level overlap;
     * the tests verify invariants for whichever interleaving occurs.
     */
    public static List<Outcome> race(List<Callable<Outcome>> tasks, Duration timeout) {
        int n = tasks.size();
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch readyLatch = new CountDownLatch(n);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>(n);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        try {
            for (Callable<Outcome> task : tasks) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    if (!startLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new TimeoutException("startLatch timed out");
                    }
                    return task.call();
                }));
            }

            if (!readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for tasks to become ready");
            }

            startLatch.countDown();

            List<Outcome> results = new ArrayList<>(Collections.nCopies(n, null));
            for (int i = 0; i < n; i++) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("Timed out waiting for task " + i + " result");
                }
                try {
                    Outcome res = futures.get(i).get(remainingNanos, TimeUnit.NANOSECONDS);
                    results.set(i, res);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new RuntimeException(cause);
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Race interrupted", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Race timed out", e);
        } finally {
            for (Future<Outcome> f : futures) {
                f.cancel(true);
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Executor did not terminate within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Executor awaitTermination interrupted", e);
            }
        }
    }
}
