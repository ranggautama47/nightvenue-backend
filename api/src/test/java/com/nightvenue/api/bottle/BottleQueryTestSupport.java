package com.nightvenue.api.bottle;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class BottleQueryTestSupport {
    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtEncoder encoder;

    protected String fixture;
    protected long venueA;
    protected long venueB;
    protected long adminId;
    protected long managerId;
    protected long cashierId;
    protected long customerUserId;
    protected long otherCustomerUserId;
    protected long noCustomerUserId;
    protected long customerId;
    protected long otherCustomerId;
    protected long productA;
    protected long productB;
    private int labelSequence;

    @BeforeEach
    protected void setUp() {
        fixture = "bq" + UUID.randomUUID().toString().substring(0, 8);
        labelSequence = 0;
        Timestamp now = Timestamp.from(Instant.now());
        venueA = insertVenue("a", now);
        venueB = insertVenue("b", now);
        adminId = insertUser("admin", "ADMIN", now);
        managerId = insertUser("manager", "MANAGER", now);
        cashierId = insertUser("cashier", "CASHIER", now);
        customerUserId = insertUser("customer", "CUSTOMER", now);
        otherCustomerUserId = insertUser("other", "CUSTOMER", now);
        noCustomerUserId = insertUser("none", "CUSTOMER", now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", managerId, venueA);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueA);
        customerId = insertCustomer("customer", customerUserId, now);
        otherCustomerId = insertCustomer("other", otherCustomerUserId, now);
        productA = insertProduct("a", venueA, now);
        productB = insertProduct("b", venueB, now);
    }

    @AfterEach
    protected void tearDown() {
        if (fixture == null) {
            return;
        }
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
        jdbc.update("DELETE FROM customers WHERE user_id IN " + userIds
                + " OR age_verified_by IN " + userIds + " OR full_name LIKE ?", like, like, like);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", like);
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", like);
        fixture = null;
    }

    protected long insertVault(long venueId, long customer, long productId, String status) {
        return insertVault(venueId, customer, productId, status, Instant.now());
    }

    protected long insertVault(long venueId, long customer, long productId, String status, Instant storedAt) {
        Timestamp now = Timestamp.from(storedAt);
        String label = fixture + "-" + ++labelSequence;
        String unique = UUID.randomUUID().toString().replace("-", "");
        String hash = unique + unique;
        return jdbc.queryForObject("""
                INSERT INTO bottle_vaults(venue_id,customer_id,product_id,source_order_item_id,label_code,
                    qr_token_hash,original_volume_ml,remaining_volume_ml,stored_at,expiry_date,status,version,created_at,updated_at)
                VALUES (?,?,?,NULL,?,?,750,750,?,?,?,0,?,?) RETURNING id
                """, Long.class, venueId, customer, productId, label, hash, now,
                Date.valueOf(LocalDate.now().plusDays(30)), status, now, now);
    }

    protected String bearer(long userId, String role) {
        Instant now = Instant.now();
        String jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                        .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build())).getTokenValue();
        return "Bearer " + jwt;
    }

    protected MockHttpServletRequestBuilder staffRequest(String auth, long venueId) {
        return get("/api/v1/bottles").header("Authorization", auth).param("venueId", Long.toString(venueId));
    }

    protected MockHttpServletRequestBuilder customerRequest(String auth) {
        return get("/api/v1/customers/me/bottles").header("Authorization", auth);
    }

    protected long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE venue_id IN "
                + "(SELECT id FROM venues WHERE code LIKE ?)", Long.class, fixture + "%");
    }

    protected long idempotencyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", Long.class, fixture + "%");
    }

    private long insertVenue(String suffix, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?,?,'Asia/Jakarta',6,true,?,?) RETURNING id
                """, Long.class, fixture + "-" + suffix, fixture + "-" + suffix, now, now);
    }

    private long insertUser(String suffix, String role, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?,'x',?,?,true,0,?,?) RETURNING id
                """, Long.class, fixture + "-" + suffix + "@test", fixture + "-" + suffix, role, now, now);
    }

    private long insertCustomer(String suffix, long userId, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO customers(user_id,full_name,age_verified,created_at,updated_at)
                VALUES (?,?,false,?,?) RETURNING id
                """, Long.class, userId, fixture + "-" + suffix, now, now);
    }

    private long insertProduct(String suffix, long venueId, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,bottle_volume_ml,is_active,version,created_at,updated_at)
                VALUES (?,?,?,1000,true,750,true,0,?,?) RETURNING id
                """, Long.class, venueId, fixture + "-" + suffix, fixture + " product " + suffix, now, now);
    }
}
