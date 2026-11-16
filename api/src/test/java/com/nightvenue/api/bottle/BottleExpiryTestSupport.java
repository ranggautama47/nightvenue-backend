package com.nightvenue.api.bottle;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class BottleExpiryTestSupport {
    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected BottleExpiryService expiry;

    @Autowired
    protected BusinessDateService businessDates;

    @Autowired
    protected Clock clock;

    @Autowired
    protected JwtEncoder encoder;

    protected String fixture;
    protected long venueA;
    protected long venueB;
    protected long userId;
    protected long customerId;
    protected long productA;
    protected long productB;
    protected long orderId;
    private int labelSequence;

    @BeforeEach
    protected void setUp() {
        fixture = "be" + UUID.randomUUID().toString().substring(0, 8);
        labelSequence = 0;
        Timestamp now = Timestamp.from(Instant.now());
        venueA = insertVenue("a", now);
        venueB = insertVenue("b", now);
        userId = insertUser(now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", userId, venueA);
        customerId = jdbc.queryForObject("""
                INSERT INTO customers(user_id,full_name,age_verified,age_verified_at,age_verified_by,created_at,updated_at)
                VALUES (?,?,true,?,?,?,?) RETURNING id
                """, Long.class, userId, fixture + "-customer", now, userId, now, now);
        productA = insertProduct("a", venueA, now);
        productB = insertProduct("b", venueB, now);
        orderId = 0;
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
        jdbc.update("DELETE FROM bottle_vault_transactions WHERE vault_id IN "
                + "(SELECT id FROM bottle_vaults WHERE venue_id IN " + venueIds + ")", like);
        jdbc.update("DELETE FROM bottle_vaults WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN " + venueIds, like);
        jdbc.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE venue_id IN " + venueIds + ")", like);
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

    protected long insertVault(String status, LocalDate expiryDate) throws Exception {
        return insertVaultWithToken(status, expiryDate, 750).id();
    }

    protected long insertVault(String status, LocalDate expiryDate, int remainingVolumeMl) throws Exception {
        return insertVaultWithToken(status, expiryDate, remainingVolumeMl).id();
    }

    protected VaultFixture insertVaultWithToken(String status, LocalDate expiryDate, int remainingVolumeMl)
            throws Exception {
        return insertVaultWithToken(venueA, customerId, productA, status, expiryDate, remainingVolumeMl);
    }

    protected VaultFixture insertVaultWithToken(
            long venueId, long customer, long productId, String status, LocalDate expiryDate, int remainingVolumeMl)
            throws Exception {
        String token = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        Timestamp now = Timestamp.from(Instant.now());
        String label = fixture + "-" + ++labelSequence;
        long id = jdbc.queryForObject("""
                INSERT INTO bottle_vaults(venue_id,customer_id,product_id,source_order_item_id,label_code,
                    qr_token_hash,original_volume_ml,remaining_volume_ml,stored_at,expiry_date,status,version,created_at,updated_at)
                VALUES (?,?,?,NULL,?,?,750,?,?,?, ?,0,?,?) RETURNING id
                """, Long.class, venueId, customer, productId, label, hash, remainingVolumeMl,
                now, Date.valueOf(expiryDate), status, now, now);
        return new VaultFixture(id, token, hash, label);
    }

    protected long insertOrder() {
        Timestamp now = Timestamp.from(Instant.now());
        orderId = jdbc.queryForObject("""
                INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,
                    discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,
                    opened_at,created_at,updated_at,version)
                VALUES (?,?,?, ?,CURRENT_DATE,'OPEN',1000,0,0,0,1000,0,0,?,?,?,0) RETURNING id
                """, Long.class, venueA, fixture + "-order", userId, customerId, now, now, now);
        return orderId;
    }

    protected String bearer() {
        Instant now = clock.instant();
        String jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                        .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", "CASHIER").build()))
                .getTokenValue();
        return "Bearer " + jwt;
    }

    protected int transactionCount(long vaultId, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bottle_vault_transactions WHERE vault_id=? AND type=?",
                Integer.class, vaultId, type);
    }

    protected String vaultStatus(long vaultId) {
        return jdbc.queryForObject("SELECT status FROM bottle_vaults WHERE id=?", String.class, vaultId);
    }

    protected int remainingVolume(long vaultId) {
        return jdbc.queryForObject("SELECT remaining_volume_ml FROM bottle_vaults WHERE id=?",
                Integer.class, vaultId);
    }

    protected MockHttpServletRequestBuilder redeemRequest(String token) {
        return post("/api/v1/bottles/redeem")
                .header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrToken\":\"" + token + "\",\"orderId\":" + orderId
                        + ",\"volumeMl\":100,\"ownerConfirmed\":true}");
    }

    protected record VaultFixture(long id, String token, String hash, String labelCode) {
    }

    private long insertVenue(String suffix, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?,?,'Asia/Jakarta',6,true,?,?) RETURNING id
                """, Long.class, fixture + "-" + suffix, fixture + "-" + suffix, now, now);
    }

    private long insertUser(Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?,'x',?,'CASHIER',true,0,?,?) RETURNING id
                """, Long.class, fixture + "@test", fixture + "-cashier", now, now);
    }

    private long insertProduct(String suffix, long venueId, Timestamp now) {
        return jdbc.queryForObject("""
                INSERT INTO products(venue_id,sku,name,price,is_bottle_keepable,bottle_volume_ml,is_active,version,created_at,updated_at)
                VALUES (?,?,?,1000,true,750,true,0,?,?) RETURNING id
                """, Long.class, venueId, fixture + "-" + suffix, fixture + " product " + suffix, now, now);
    }
}
