package com.nightvenue.api.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRepositoryIntegrationTests extends PaymentIntegrationTestSupport {
    @Autowired PlatformTransactionManager transactionManager;

    @Test void findForUpdate_serializesConcurrentTransactions() throws Exception {
        Payment payment = createPayment(PaymentMethod.CASH, PaymentStatus.PAID, 100, createShift(0).id());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assertTrue(payments.findForUpdate(payment.id()).isPresent());
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                assertTrue(payments.findForUpdate(payment.id()).isPresent());
            }));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "second SELECT FOR UPDATE must wait for the first transaction");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void existsActiveByOrder_countsPendingAndPaidOnly() {
        var shift = createShift(0);
        createPayment(PaymentMethod.CASH, PaymentStatus.FAILED, 50, shift.id());
        assertFalse(payments.existsActiveByOrder(orderId));
        createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 70, null);
        assertTrue(payments.existsActiveByOrder(orderId));
        createPayment(PaymentMethod.CASH, PaymentStatus.PAID, 30, shift.id());
        assertTrue(payments.existsActiveByOrder(orderId));
    }

    @Test void sumByStatus_excludesFailedAndExpired() {
        var shift = createShift(0);
        createPayment(PaymentMethod.CASH, PaymentStatus.PAID, 110, shift.id());
        createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 50, null);
        createPayment(PaymentMethod.CASH, PaymentStatus.FAILED, 70, shift.id());
        createPayment(PaymentMethod.CASH, PaymentStatus.EXPIRED, 90, shift.id());
        assertEquals(160, payments.sumActiveByOrder(orderId));
        assertEquals(70, payments.sumByStatus(orderId, PaymentStatus.FAILED));
        assertEquals(90, payments.sumByStatus(orderId, PaymentStatus.EXPIRED));
    }

    @Test void findByOrder_supportsMultiplePayments() {
        var shift = createShift(0);
        createPayment(PaymentMethod.CASH, PaymentStatus.PAID, 100, shift.id());
        createPayment(PaymentMethod.CARD_EDC, PaymentStatus.PAID, 200, null);
        List<Payment> found = payments.findByOrder(orderId);
        assertEquals(2, found.size());
        assertEquals(List.of(100L, 200L), found.stream().map(Payment::amount).toList());
    }

    @Test void providerOrderId_lookup_returnsCorrectPayment() {
        Payment payment = createPayment(PaymentMethod.QRIS_MIDTRANS, PaymentStatus.PENDING, 100, null);
        assertEquals(payment.id(), payments.findByProviderOrderId(payment.providerOrderId()).orElseThrow().id());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for transaction release", e);
        }
    }
}
