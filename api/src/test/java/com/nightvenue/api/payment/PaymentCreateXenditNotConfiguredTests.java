package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.oauth2.jwt.JwsHeader.with;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6.7: a blank Xendit secret must answer 503 PROVIDER_NOT_CONFIGURED BEFORE
 * any idempotency row, payment row or provider request is created. Separate
 * Spring context because the secret is a context-level property.
 */
@SpringBootTest(properties = "nightvenue.seed.enabled=false") // secret-key left blank on purpose
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PaymentCreateXenditNotConfiguredTests {
    private static final String API = "/api/v1/orders";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    private String fixture;
    private long venueId, cashierId, orderId;
    private final List<UUID> keys = new ArrayList<>();

    @DynamicPropertySource
    static void xenditBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.payment.xendit.base-url", () -> "http://localhost:" + XenditStub.port());
    }

    @BeforeEach
    void setup() {
        fixture = "M3BN-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'QRIS NC Test', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@test.local", now, now);
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        orderId = jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', 10000, 10000, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, fixture, cashierId, now, now, now);
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM audit_logs WHERE venue_id = ? OR actor_user_id = ?", venueId, cashierId);
        jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM user_venues WHERE user_id = ?", cashierId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void blankSecret_503_noIdempotencyRow_noPaymentRow_noProviderCall() throws Exception {
        UUID key = UUID.randomUUID();
        keys.add(key);
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        int stubCountBefore = XenditStub.count();

        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer()).header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"QRIS\",\"amount\":10000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("PROVIDER_NOT_CONFIGURED"));

        assertEquals(stubCountBefore, XenditStub.count()); // zero provider requests
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    private String bearer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(cashierId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", "CASHIER").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
