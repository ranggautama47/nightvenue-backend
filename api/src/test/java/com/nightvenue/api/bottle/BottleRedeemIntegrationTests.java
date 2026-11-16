package com.nightvenue.api.bottle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class BottleRedeemIntegrationTests extends BottleRedeemTestSupport {

    @Autowired
    private Clock clock;

    // Runs after BottleRedeemTestSupport.setUpCommon() (superclass @BeforeEach runs first).
    @BeforeEach
    void alignVaultExpiryWithClock() {
        jdbc.update("UPDATE bottle_vaults SET expiry_date=? WHERE id=?",
                Date.valueOf(businessToday().plusDays(30)), vault);
    }

    // ---------- helpers (names do not collide with BottleRedeemTestSupport) ----------

    private LocalDate businessToday() {
        // venue fixture: Asia/Jakarta, cutoff hour 6
        return clock.instant().atZone(ZoneId.of("Asia/Jakarta")).minusHours(6).toLocalDate();
    }

    private int intOf(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    private int vaultRemaining() {
        return intOf("SELECT remaining_volume_ml FROM bottle_vaults WHERE id=?", vault);
    }

    private int redeemTxCount() {
        return intOf("SELECT COUNT(*) FROM bottle_vault_transactions WHERE vault_id=? AND type='REDEEM'", vault);
    }

    private int redemptionItemsInFixtureVenues() {
        return intOf("SELECT COUNT(*) FROM order_items WHERE item_type='BOTTLE_REDEMPTION' AND order_id IN "
                + "(SELECT o.id FROM orders o JOIN venues v ON v.id=o.venue_id WHERE v.code LIKE ?)", fixture + "%");
    }

    private void assertNothingRedeemed() {
        assertThat(vaultRemaining()).isEqualTo(750);
        assertThat(redeemTxCount()).isZero();
        assertThat(redemptionItemsInFixtureVenues()).isZero();
    }

    private long newVenue(String suffix) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject(
                "INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at) VALUES (?,?,'Asia/Jakarta',6,true,?,?) RETURNING id",
                Long.class, fixture + suffix, fixture + suffix, now, now);
    }

    private long newUser(String suffix) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject(
                "INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at) VALUES (?,'x','Cashier','CASHIER',true,0,?,?) RETURNING id",
                Long.class, fixture + suffix + "@test", now, now);
    }

    private long newCustomer() {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject(
                "INSERT INTO customers(full_name,age_verified,age_verified_at,age_verified_by,created_at,updated_at) VALUES ('Other',true,?,?,?,?) RETURNING id",
                Long.class, now, user, now, now);
    }

    private long newOrder(long venueId, long customerId, String suffix, long totalDue) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject(
                "INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version) VALUES (?,?,?,?,CURRENT_DATE,'OPEN',?,0,0,0,?,0,0,?,?,?,0) RETURNING id",
                Long.class, venueId, fixture + suffix, user, customerId, totalDue, totalDue, now, now, now);
    }

    private MockHttpServletRequestBuilder redeemAs(String auth, String body) {
        return post("/api/v1/bottles/redeem")
                .header("Authorization", auth)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // ---------- happy paths ----------

    @Test
    void partialRedeemAddsZeroValueServedItemWithoutChangingOrderTotals() throws Exception {
        int inventoryBefore = intOf("SELECT COUNT(*) FROM inventory_transactions");

        mvc.perform(redeem(UUID.randomUUID(), json(250)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.redeemedVolumeMl").value(250))
                .andExpect(jsonPath("$.remainingVolumeMl").value(500))
                .andExpect(jsonPath("$.vaultStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.qrToken").doesNotExist());

        assertThat(vaultRemaining()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT status FROM bottle_vaults WHERE id=?", String.class, vault)).isEqualTo("ACTIVE");
        assertThat(intOf("SELECT COUNT(*) FROM bottle_vault_transactions WHERE vault_id=? AND type='REDEEM' AND volume_delta_ml=-250 AND remaining_after_ml=500 AND owner_confirmed AND performed_by=?", vault, user)).isEqualTo(1);
        assertThat(intOf("SELECT COUNT(*) FROM order_items WHERE order_id=? AND item_type='BOTTLE_REDEMPTION' AND unit_price_snapshot=0 AND line_total=0 AND quantity=1 AND status='SERVED' AND product_id=?", order, product)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subtotal||'/'||total_due||'/'||paid_total FROM orders WHERE id=?", String.class, order)).isEqualTo("1000/1000/0");
        assertThat(intOf("SELECT COUNT(*) FROM inventory_transactions")).isEqualTo(inventoryBefore);
    }

    @Test
    void fullRedeemEmptiesVaultAndFurtherRedeemReturns422() throws Exception {
        mvc.perform(redeem(UUID.randomUUID(), json(750)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingVolumeMl").value(0))
                .andExpect(jsonPath("$.vaultStatus").value("EMPTY"));

        assertThat(vaultRemaining()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM bottle_vaults WHERE id=?", String.class, vault)).isEqualTo("EMPTY");

        mvc.perform(redeem(UUID.randomUUID(), json(1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(redeemTxCount()).isEqualTo(1);
    }

    // ---------- 404 family ----------

    @Test
    void unknownTokenReturns404() throws Exception {
        mvc.perform(redeem(UUID.randomUUID(), jsonWithToken(UUID.randomUUID().toString(), 100, order, true)))
                .andExpect(status().isNotFound());
        assertNothingRedeemed();
    }

    @Test
    void otherCustomersOrderReturns404() throws Exception {
        long otherCustomer = newCustomer();
        long otherOrder = newOrder(venue, otherCustomer, "-oc", 1000);

        mvc.perform(redeem(UUID.randomUUID(), jsonWithOrder(100, otherOrder)))
                .andExpect(status().isNotFound());
        assertNothingRedeemed();
    }

    @Test
    void walkInOrderCustomerNullReturns404() throws Exception {
        long walkInOrder = newOrder(venue, customer, "-walkin", 1000);
        jdbc.update("UPDATE orders SET customer_id=NULL WHERE id=?", walkInOrder);

        mvc.perform(redeem(UUID.randomUUID(), jsonWithOrder(100, walkInOrder)))
                .andExpect(status().isNotFound());
        assertNothingRedeemed();
    }

    @Test
    void crossVenueVaultReturns404() throws Exception {
        long venue2 = newVenue("-v2");
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", user, venue2);
        long orderInVenue2 = newOrder(venue2, customer, "-o2", 1000);

        // vault belongs to venue 1, order belongs to venue 2, actor has access to both
        mvc.perform(redeem(UUID.randomUUID(), jsonWithOrder(100, orderInVenue2)))
                .andExpect(status().isNotFound());
        assertNothingRedeemed();
    }

    @Test
    void actorWithoutVenueAccessReturns404() throws Exception {
        long noAccessUser = newUser("-noaccess"); // no user_venues row

        mvc.perform(redeemAs(bearer(noAccessUser, "CASHIER"), json(100)))
                .andExpect(status().isNotFound());
        assertNothingRedeemed();
    }

    // ---------- validation / business rules ----------

    @Test
    void ownerConfirmedFalseReturns422NoStateChange() throws Exception {
        mvc.perform(redeem(UUID.randomUUID(), jsonWithVolumeAndOrder(10, order, false)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    @Test
    void ownerConfirmedMissingReturns400() throws Exception {
        mvc.perform(redeem(UUID.randomUUID(),
                        "{\"qrToken\":\"" + token + "\",\"orderId\":" + order + ",\"volumeMl\":10}"))
                .andExpect(status().isBadRequest());
        assertNothingRedeemed();
    }

    @Test
    void expiredVaultReturns422() throws Exception {
        jdbc.update("UPDATE bottle_vaults SET expiry_date=? WHERE id=?",
                Date.valueOf(businessToday().minusDays(1)), vault);

        mvc.perform(redeem(UUID.randomUUID(), json(100)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    @Test
    void redeemableOnExpiryDateItselfReturns201() throws Exception {
        jdbc.update("UPDATE bottle_vaults SET expiry_date=? WHERE id=?",
                Date.valueOf(businessToday()), vault);

        mvc.perform(redeem(UUID.randomUUID(), json(100)))
                .andExpect(status().isCreated());
        assertThat(vaultRemaining()).isEqualTo(650);
    }

    @Test
    void volumeExceedsRemainingReturns422() throws Exception {
        mvc.perform(redeem(UUID.randomUUID(), json(751)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveVolumeReturns400(int ml) throws Exception {
        mvc.perform(redeem(UUID.randomUUID(), json(ml)))
                .andExpect(status().isBadRequest());
        assertNothingRedeemed();
    }

    @Test
    void orderStatusPaidReturns422() throws Exception {
        setOrderPaid(order);
        mvc.perform(redeem(UUID.randomUUID(), json(100)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    @Test
    void orderStatusVoidedReturns422() throws Exception {
        jdbc.update("UPDATE orders SET status='VOIDED' WHERE id=?", order);
        mvc.perform(redeem(UUID.randomUUID(), json(100)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    @Test
    void orderTotalDueZeroReturns422() throws Exception {
        long zeroOrder = newOrder(venue, customer, "-zero", 0);
        mvc.perform(redeem(UUID.randomUUID(), jsonWithOrder(100, zeroOrder)))
                .andExpect(status().isUnprocessableEntity());
        assertNothingRedeemed();
    }

    // ---------- idempotency ----------

    @Test
    void replaySameKeySamePayloadReturnsSameBodyAndExactlyOneRedeemRow() throws Exception {
        UUID key = UUID.randomUUID();
        MvcResult first = mvc.perform(redeem(key, json(200)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mvc.perform(redeem(key, json(200)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(redeemTxCount()).isEqualTo(1);
        assertThat(intOf("SELECT COUNT(*) FROM order_items WHERE order_id=? AND item_type='BOTTLE_REDEMPTION'", order)).isEqualTo(1);
        assertThat(vaultRemaining()).isEqualTo(550);
    }

    @Test
    void sameKeyDifferentPayloadReturns422() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(redeem(key, json(100))).andExpect(status().isCreated());
        mvc.perform(redeem(key, json(200))).andExpect(status().isUnprocessableEntity());
        assertThat(redeemTxCount()).isEqualTo(1);
        assertThat(vaultRemaining()).isEqualTo(650);
    }

    // ---------- leak ----------

    @Test
    void leakRawTokenAbsentFromLogsAuditIdempotencyAndResponse(CapturedOutput output) throws Exception {
        UUID key = UUID.randomUUID();
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));

        MvcResult result = mvc.perform(redeem(key, json(100)))
                .andExpect(status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(token).doesNotContain(hash);

        // whole-table scans (row text) so no column names are guessed
        String audit = jdbc.queryForObject(
                "SELECT COALESCE(string_agg(t::text, ' '), '') FROM audit_logs t", String.class);
        assertThat(audit).doesNotContain(token).doesNotContain(hash);

        String idem = jdbc.queryForObject(
                "SELECT COALESCE(string_agg(t::text, ' '), '') FROM idempotency_keys t", String.class);
        assertThat(idem).contains(key.toString());          // positive control: the row exists and is scanned
        assertThat(idem).doesNotContain(token).doesNotContain(hash);

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(hash);
    }

    // ---------- authorization ----------

    @Test
    void authorizationMatrix() throws Exception {
        mvc.perform(redeemAs(bearer(user, "CASHIER"), json(100))).andExpect(status().isCreated());
        mvc.perform(redeemAs(bearer(user, "MANAGER"), json(100))).andExpect(status().isCreated());
        mvc.perform(redeemAs(bearer(user, "ADMIN"), json(100))).andExpect(status().isCreated());
        assertThat(vaultRemaining()).isEqualTo(450);

        mvc.perform(redeemAs(bearer(user, "CUSTOMER"), json(100))).andExpect(status().isForbidden());
        assertThat(vaultRemaining()).isEqualTo(450);

        mvc.perform(post("/api/v1/bottles/redeem")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(100)))
                .andExpect(status().isUnauthorized());
        assertThat(vaultRemaining()).isEqualTo(450);
    }
}