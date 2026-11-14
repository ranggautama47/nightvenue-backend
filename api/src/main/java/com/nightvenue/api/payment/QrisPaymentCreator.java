package com.nightvenue.api.payment;

import com.nightvenue.api.common.ProviderNotConfigured;
import com.nightvenue.api.payment.xendit.XenditClient;
import com.nightvenue.api.payment.xendit.XenditProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * QRIS create orchestrator. Deliberately NOT @Transactional: the sequence is
 * TX1 -> external Xendit HTTP call -> TX2, so no DB transaction or row lock is
 * held while waiting for the provider. CASH/CARD_EDC keeps the existing
 * PaymentService.createPayment path untouched.
 */
@Service
public class QrisPaymentCreator {
    private static final String QR_REJECTED_HEADER = "X-Error-Code";

    private final QrisCreateTransactions transactions;
    private final XenditClient xendit;
    private final XenditProperties properties;
    private final Clock clock;

    public QrisPaymentCreator(QrisCreateTransactions transactions, XenditClient xendit,
                              XenditProperties properties, Clock clock) {
        this.transactions = transactions;
        this.xendit = xendit;
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseEntity<QrisPaymentView> create(long orderId, long amount, String referenceNote,
                                                  UUID key, String jwtSubject) {
        Instant now = clock.instant();
        // gate BEFORE reserving anything: no idempotency row, no payment row, no HTTP call
        if (!properties.isConfigured()) {
            throw new ProviderNotConfigured();
        }
        QrisCreateTransactions.BeginResult begin =
                transactions.begin(orderId, amount, referenceNote, key, jwtSubject, now);
        if (begin.replay()) {
            return ResponseEntity.status(begin.replayStatus()).body(begin.replayView());
        }
        // external call: no DB transaction, no row lock (TX1 already committed)
        XenditClient.CreateResult result = xendit.createQris(begin.referenceId(), amount);
        QrisCreateTransactions.FinishResult finish =
                transactions.finish(begin.paymentId(), key, jwtSubject, result);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(finish.httpStatus());
        if (finish.errorCodeHeader() != null) {
            response.header(QR_REJECTED_HEADER, finish.errorCodeHeader());
        }
        return response.body(finish.view());
    }
}
