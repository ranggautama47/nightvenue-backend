package com.nightvenue.api.bottle;

import com.nightvenue.api.TestcontainersConfig;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class BottleRedeemTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtEncoder encoder;

    protected String fixture;
    protected long venue;
    protected long user;
    protected long customer;
    protected long product;
    protected long order;
    protected long vault;
    protected String token;

    @BeforeEach
    protected void setUpCommon() throws Exception {
        fixture = "br" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        venue = jdbc.queryForObject(
                "INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at) VALUES (?,?,'Asia/Jakarta',6,true,?,?) RETURNING id",
                Long.class, fixture, fixture, now, now);
        user = jdbc.queryForObject(
                "INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at) VALUES (?,'x','Cashier','CASHIER',true,0,?,?) RETURNING id",
                Long.class, fixture + "@test", now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", user, venue);
        customer = jdbc.queryForObject(
                "INSERT INTO customers(full_name,age_verified,age_verified_at,age_verified_by,created_at,updated_at) VALUES ('Customer',true,?,?,?,?) RETURNING id",
                Long.class, now, user, now, now);
        product = jdbc.queryForObject(
                "INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,bottle_volume_ml,is_active,version,created_at,updated_at) VALUES (?,?,'Gin',1000,true,750,true,0,?,?) RETURNING id",
                Long.class, venue, fixture, now, now);
        order = jdbc.queryForObject(
                "INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version) VALUES (?,?,?, ?,CURRENT_DATE,'OPEN',1000,0,0,0,1000,0,0,?,?,?,0) RETURNING id",
                Long.class, venue, fixture, user, customer, now, now, now);
        token = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        vault = jdbc.queryForObject(
                "INSERT INTO bottle_vaults(venue_id,customer_id,product_id,source_order_item_id,label_code,original_volume_ml,remaining_volume_ml,qr_token_hash,status,stored_at,expiry_date,version,created_at,updated_at) VALUES (?,?,?,NULL,?,750,750,?,'ACTIVE',?,CURRENT_DATE + 30,0,?,?) RETURNING id",
                Long.class, venue, customer, product, "BV-" + fixture, hash, now, now, now);
    }

    @AfterEach
        protected void tearDownCommon() {
        if (fixture == null) return;
        String like = fixture + "%";
        String venueIds = "(SELECT id FROM venues WHERE code LIKE ?)";
        String userIds = "(SELECT id FROM users WHERE email LIKE ?)";
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id IN " + userIds, like);
        jdbc.update("DELETE FROM bottle_vault_transactions WHERE vault_id IN (SELECT id FROM bottle_vaults WHERE venue_id IN " + venueIds + ")", like);
        jdbc.update("DELETE FROM bottle_vaults WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id IN " + venueIds + ")", like);
        jdbc.update("DELETE FROM orders WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM products WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM user_venues WHERE user_id IN " + userIds, like);
        jdbc.update("DELETE FROM customers WHERE age_verified_by IN " + userIds, like);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", like);
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", like);
    }

    protected String bearer(long userId, String role) {
        Instant now = Instant.now();
        String jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("nightvenue")
                        .subject(Long.toString(userId))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", role)
                        .build())).getTokenValue();
        return "Bearer " + jwt;
    }

    protected String bearer() {
        return bearer(user, "CASHIER");
    }

    protected MockHttpServletRequestBuilder redeem(UUID key, String body) {
        return post("/api/v1/bottles/redeem")
                .header("Authorization", bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    protected String json(int ml) {
        return "{\"qrToken\":\"" + token + "\",\"orderId\":" + order + ",\"volumeMl\":" + ml + ",\"ownerConfirmed\":true}";
    }

    protected String jsonWithOrder(int ml, long orderId) {
        return "{\"qrToken\":\"" + token + "\",\"orderId\":" + orderId + ",\"volumeMl\":" + ml + ",\"ownerConfirmed\":true}";
    }

    protected String jsonWithVolumeAndOrder(int ml, long orderId, boolean ownerConfirmed) {
        return "{\"qrToken\":\"" + token + "\",\"orderId\":" + orderId + ",\"volumeMl\":" + ml + ",\"ownerConfirmed\":" + ownerConfirmed + "}";
    }

    protected String jsonWithToken(String qrToken, int ml, long orderId, boolean ownerConfirmed) {
        return "{\"qrToken\":\"" + qrToken + "\",\"orderId\":" + orderId + ",\"volumeMl\":" + ml + ",\"ownerConfirmed\":" + ownerConfirmed + "}";
    }

    protected String createOrder(long cashierId, long custId) {
        Timestamp now = Timestamp.from(Instant.now());
        return String.valueOf(jdbc.queryForObject(
                "INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version) VALUES (?,?,?, ?,CURRENT_DATE,'OPEN',1000,0,0,0,1000,0,0,?,?,?,0) RETURNING id",
                Long.class, venue, fixture + "-" + UUID.randomUUID().toString().substring(0, 8), cashierId, custId, now, now, now));
    }

    protected String createOrderWithTotal(long cashierId, long custId, long totalDue) {
        Timestamp now = Timestamp.from(Instant.now());
        return String.valueOf(jdbc.queryForObject(
                "INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version) VALUES (?,?,?, ?,CURRENT_DATE,'OPEN',?,0,0,0,?,0,0,?,?,?,0) RETURNING id",
                Long.class, venue, fixture + "-" + UUID.randomUUID().toString().substring(0, 8), cashierId, custId, totalDue, totalDue, now, now, now));
    }

    protected void setOrderPaid(long orderId) {
        jdbc.update("UPDATE orders SET status='PAID', paid_total=total_due WHERE id=?", orderId);
    }

    protected String createVaultForCustomer(long custId, int volume, String status, LocalDate expiryDate) throws Exception {
        String t = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));
        Timestamp now = Timestamp.from(Instant.now());
        return String.valueOf(jdbc.queryForObject(
                "INSERT INTO bottle_vaults(venue_id,customer_id,product_id,source_order_item_id,label_code,original_volume_ml,remaining_volume_ml,qr_token_hash,status,stored_at,expiry_date,version,created_at,updated_at) VALUES (?,?,?,NULL,?,?,?,?,?,?,?,0,?,?) RETURNING id",
                Long.class, venue, custId, product, "BV-" + fixture + "-" + UUID.randomUUID().toString().substring(0, 8), volume, volume, hash, status, now, expiryDate, now, now));
    }

    protected String createVaultWithTokenForCustomer(long custId, int volume, String status, LocalDate expiryDate, String rawToken) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        Timestamp now = Timestamp.from(Instant.now());
        return String.valueOf(jdbc.queryForObject(
                "INSERT INTO bottle_vaults(venue_id,customer_id,product_id,source_order_item_id,label_code,original_volume_ml,remaining_volume_ml,qr_token_hash,status,stored_at,expiry_date,version,created_at,updated_at) VALUES (?,?,?,NULL,?,?,?,?,?,?,?,0,?,?) RETURNING id",
                Long.class, venue, custId, product, "BV-" + fixture + "-" + UUID.randomUUID().toString().substring(0, 8), volume, volume, hash, status, now, expiryDate, now, now));
    }

    protected void deleteBottleRows() {
        jdbc.update("DELETE FROM bottle_vault_transactions WHERE vault_id=?", vault);
        jdbc.update("DELETE FROM bottle_vaults WHERE id=?", vault);
    }
}