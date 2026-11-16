package com.nightvenue.api.bottle;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class BottleRedeemConcurrencyTests extends BottleRedeemTestSupport {

    @Test
    void parallelRedeemsNeverOverdrawVault() throws Exception {
        int n = 6;
        String auth = bearer();
        var latch = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var pool = Executors.newFixedThreadPool(n);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            long orderId = jdbc.queryForObject(
                    "INSERT INTO orders(venue_id,order_number,cashier_id,customer_id,business_date,status,subtotal,discount_amount,service_charge_amount,tax_amount,total_due,paid_total,minimum_spend_snapshot,opened_at,created_at,updated_at,version) VALUES (?,?,?,?,CURRENT_DATE,'OPEN',1000,0,0,0,1000,0,0,now(),now(),now(),0) RETURNING id",
                    Long.class, venue, fixture + "-" + i, user, customer);

            String body = jsonWithOrder(250, orderId);
            pool.submit(() -> {
                try {
                    latch.await();
                    int status = mvc.perform(post("/api/v1/bottles/redeem")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    statuses.add(status);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(errors).isEmpty();
        assertThat(statuses).hasSize(n).allMatch(s -> s == 201 || s == 422 || s == 409);

        int successes = (int) statuses.stream().filter(s -> s == 201).count();
        int remaining = jdbc.queryForObject("SELECT remaining_volume_ml FROM bottle_vaults WHERE id=?", Integer.class, vault);
        assertThat(remaining).isGreaterThanOrEqualTo(0);
        assertThat(750 - remaining).isEqualTo(successes * 250);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bottle_vault_transactions WHERE vault_id=? AND type='REDEEM'", Integer.class, vault)).isEqualTo(successes);
    }
}