package com.nightvenue.api.bottle;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public class BottleConcurrencyTests {

    private static final String BOTTLE_URL = "/api/v1/bottles";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    private String fixture;
    private long venueId;
    private long cashierId;
    private long customerId;
    private long productId;
    private long orderId;
    private long orderItemId;

    private final Set<UUID> recordedKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        fixture = "bc" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());

        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture + "-v", fixture + "-v", now, now);

        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'hash', 'Cashier', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture + "-c@nv.test", now, now);

        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueId);

        customerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, age_verified, age_verified_at, age_verified_by, created_at, updated_at)
                VALUES (?, true, ?, ?, ?, ?) RETURNING id
                """, Long.class, fixture + "-cust", now, cashierId, now, now);

        productId = jdbc.queryForObject("""
                INSERT INTO products (venue_id, sku, name, price, is_bottle_keepable, bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, 'Whiskey', 1500000, true, 700, true, 0, ?, ?) RETURNING id
                """, Long.class, venueId, "SKU-" + fixture, now, now);

        orderId = jdbc.queryForObject("""
                INSERT INTO orders (
                    venue_id, order_number, cashier_id, customer_id, business_date, status,
                    subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                    minimum_spend_snapshot, opened_at, created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, CURRENT_DATE, 'PAID', 1500000, 0, 0, 0, 1500000, 1500000, 0, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, venueId, "NV-" + fixture, cashierId, customerId, now, now, now);

        orderItemId = jdbc.queryForObject("""
                INSERT INTO order_items (
                    order_id, product_id, item_type, product_name_snapshot,
                    unit_price_snapshot, quantity, line_total, status, created_at, updated_at
                )
                VALUES (?, ?, 'PRODUCT', 'Whiskey', 1500000, 1, 1500000, 'ORDERED', ?, ?)
                RETURNING id
                """, Long.class, orderId, productId, now, now);
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) return;
        for (UUID key : recordedKeys) {
            jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key);
        }
        jdbc.update("DELETE FROM bottle_vault_transactions WHERE vault_id IN (SELECT id FROM bottle_vaults WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM bottle_vaults WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM products WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM user_venues WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void concurrentDepositsOnSameItemResultInExactlyOneSuccessAndRemainingConflicts() throws Exception {
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        String authHeader = bearer(cashierId, "CASHIER");
        String payload = "{\"orderId\":" + orderId + ",\"orderItemId\":" + orderItemId + "}";

        for (int i = 0; i < threads; i++) {
            UUID key = UUID.randomUUID();
            recordedKeys.add(key);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int status = mvc.perform(post(BOTTLE_URL)
                                    .header("Authorization", authHeader)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn()
                            .getResponse()
                            .getStatus();

                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);

        // Verify exactly one bottle_vaults row exists for this item
        Integer vaultCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bottle_vaults WHERE source_order_item_id = ?", Integer.class, orderItemId);
        assertThat(vaultCount).isEqualTo(1);

        // Verify exactly one transaction exists
        Integer txCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bottle_vault_transactions
                WHERE vault_id IN (SELECT id FROM bottle_vaults WHERE source_order_item_id = ?)
                """, Integer.class, orderItemId);
        assertThat(txCount).isEqualTo(1);

        // Verify exactly one audit log entry exists
        Integer auditCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'BOTTLE_VAULT' AND action = 'BOTTLE_DEPOSIT'
                  AND entity_id IN (SELECT id FROM bottle_vaults WHERE source_order_item_id = ?)
                """, Integer.class, orderItemId);
        assertThat(auditCount).isEqualTo(1);
    }

    private String bearer(long userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

