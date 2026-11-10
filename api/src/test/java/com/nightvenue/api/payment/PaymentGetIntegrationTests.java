package com.nightvenue.api.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.oauth2.jwt.JwsHeader.with;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(com.nightvenue.api.TestcontainersConfig.class)
class PaymentGetIntegrationTests {
    private static final String API = "/api/v1/payments";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    private String fixture;
    private long venueId, venueBId, cashierId, orderIdA, orderIdB;

    @BeforeEach
    void setup() {
        fixture = "M3A-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'Payment Get Test', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@test.local", now, now);
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        venueBId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture + "-B", fixture + "-B", now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        orderIdA = insertOrder(venueId, fixture);
        orderIdB = insertOrder(venueBId, fixture + "-B");
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM payments WHERE order_id IN (?, ?)", orderIdA, orderIdB);
        jdbc.update("DELETE FROM orders WHERE id IN (?, ?)", orderIdA, orderIdB);
        jdbc.update("DELETE FROM user_venues WHERE user_id = ?", cashierId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, venueBId);
    }

    private long insertOrder(long venue, String orderNumber) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', 10000, 10000, ?, ?, ?, 0) RETURNING id
                """, Long.class, venue, orderNumber, cashierId, now, now, now);
    }

    private long insertCardPayment(long order, long venue) {
        return jdbc.queryForObject("""
                INSERT INTO payments (order_id, venue_id, business_date, method, amount, status,
                                      shift_id, paid_at, created_by, created_at, updated_at, version)
                VALUES (?, ?, CURRENT_DATE, 'CARD_EDC', 5000, 'PAID', NULL, now(), ?, now(), now(), 0)
                RETURNING id
                """, Long.class, order, venue, cashierId);
    }

    private String bearer() {
        return bearer("CASHIER");
    }

    private String bearer(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(cashierId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void getPayment_customerRoleForbidden() throws Exception {
        long paymentId = insertCardPayment(orderIdA, venueId);
        mvc.perform(get(API + "/" + paymentId).header("Authorization", bearer("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPayment_anonymousRequestUnauthorized() throws Exception {
        long paymentId = insertCardPayment(orderIdA, venueId);
        mvc.perform(get(API + "/" + paymentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPayment_returnsCardPaymentWithinOwnVenue() throws Exception {
        long paymentId = insertCardPayment(orderIdA, venueId);
        mvc.perform(get(API + "/" + paymentId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.orderId").value(orderIdA))
                .andExpect(jsonPath("$.venueId").value(venueId))
                .andExpect(jsonPath("$.method").value("CARD_EDC"))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void getPayment_responseExposesNoProviderOrChargeFields() throws Exception {
        long paymentId = insertCardPayment(orderIdA, venueId);
        String body = mvc.perform(get(API + "/" + paymentId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String forbidden : new String[]{
                "provider", "providerOrderId", "providerTransactionId", "providerIdempotencyKey",
                "chargeState", "chargeStartedAt", "chargeAttempts"}) {
            if (body.contains(forbidden)) {
                throw new AssertionError("Response exposes internal field: " + forbidden + " in " + body);
            }
        }
    }

    @Test
    void getPayment_unknownPaymentReturns404() throws Exception {
        mvc.perform(get(API + "/999999999").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPayment_crossVenuePaymentReturns404() throws Exception {
        // caller only has venueId in user_venues (setup); venueBId is inaccessible
        long crossVenuePaymentId = insertCardPayment(orderIdB, venueBId);
        long ownVenuePaymentId = insertCardPayment(orderIdA, venueId);
        mvc.perform(get(API + "/" + crossVenuePaymentId).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        // same call pattern stays available within the caller's venue (no overblocking)
        mvc.perform(get(API + "/" + ownVenuePaymentId).header("Authorization", bearer()))
                .andExpect(status().isOk());
    }
}
