package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.shift.CashierShift;
import com.nightvenue.api.shift.ShiftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class PaymentIntegrationTestSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PaymentRepository payments;
    @Autowired protected PaymentEventRepository events;
    @Autowired protected ShiftRepository shifts;

    protected String fixture;
    protected long venueId;
    protected long cashierId;
    protected long orderId;

    @BeforeEach
    protected void createFixture() {
        fixture = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'Payment Test', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@test.local", now, now);
        orderId = jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', 10000, 10000, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, fixture, cashierId, now, now, now);
    }

    @AfterEach
    protected void deleteFixture() {
        jdbc.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE order_id = ?)", orderId);
        jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    protected CashierShift createShift(long openingFloat) {
        return shifts.insertOpenShift(venueId, cashierId, LocalDate.now(), openingFloat, Instant.now());
    }

    protected Payment createPayment(PaymentMethod method, PaymentStatus status, long amount, Long shiftId) {
        Instant now = Instant.now();
        boolean qris = method == PaymentMethod.QRIS_MIDTRANS;
        return payments.insert(orderId, venueId, LocalDate.now(), shiftId, method, amount, status,
                qris ? "MIDTRANS" : null, qris ? fixture + "-provider-order" : null, null,
                qris ? UUID.randomUUID() : null, qris ? ChargeState.NOT_SENT : null, null, 0, null,
                null, status == PaymentStatus.PAID ? now : null, cashierId, now);
    }
}
