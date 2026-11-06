package com.nightvenue.api.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PaymentEventRepositoryIntegrationTests extends PaymentIntegrationTestSupport {
    @Autowired PlatformTransactionManager transactionManager;

    @Test void insertNewEvent_returnsOneInsertedRow() {
        Payment payment = createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 100, null);
        assertEquals(1, events.insert(payment.id(), "MIDTRANS", fixture + "-event", PaymentEventResult.PROCESSED,
                "{\"transaction_id\":\"tx-1\"}", Instant.now()));
    }

    @Test void insertDuplicateEvent_returnsZeroInsertedRows() {
        Payment payment = createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 100, null);
        String key = fixture + "-event";
        assertEquals(1, events.insert(payment.id(), "MIDTRANS", key, PaymentEventResult.PROCESSED, "{}", Instant.now()));
        assertEquals(0, events.insert(payment.id(), "MIDTRANS", key, PaymentEventResult.IGNORED_STALE, "{}", Instant.now()));
    }

    @Test void concurrentDuplicateEvent_insertsExactlyOneRow() throws Exception {
        Payment payment = createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 100, null);
        String key = fixture + "-event";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            Future<Integer> first = executor.submit(() -> insertTogether(transaction, ready, start, payment.id(), key));
            Future<Integer> second = executor.submit(() -> insertTogether(transaction, ready, start, payment.id(), key));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE event_key = ?", Integer.class, key));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void payloadSnapshot_persistsSanitizedFields() {
        Payment payment = createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 100, null);
        String payload = "{\"transaction_id\":\"tx-1\",\"status_code\":\"200\"}";
        events.insert(payment.id(), "MIDTRANS", fixture + "-event", PaymentEventResult.PROCESSED, payload, Instant.now());
        String stored = jdbc.queryForObject("SELECT payload_snapshot::text FROM payment_events WHERE payment_id = ?", String.class, payment.id());
        assertTrue(stored.contains("transaction_id"));
        assertTrue(stored.contains("status_code"));
        assertFalse(stored.contains("server_key"));
    }

    private int insertTogether(TransactionTemplate transaction, CountDownLatch ready, CountDownLatch start,
                              long paymentId, String eventKey) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting to start inserts");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start inserts", e);
        }
        return transaction.execute(status -> events.insert(paymentId, "MIDTRANS", eventKey,
                PaymentEventResult.PROCESSED, "{}", Instant.now()));
    }
}
