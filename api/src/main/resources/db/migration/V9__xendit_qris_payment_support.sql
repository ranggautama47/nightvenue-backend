-- V9__xendit_qris_payment_support.sql
-- Adds Xendit dynamic QRIS support to the payments foundation.
-- V1-V8 migrations remain immutable.

-- 1. Add correlation and payload columns
-- provider_reference_id VARCHAR(64): NightVenue-generated stable reference sent as Xendit
--   reference_id (Xendit allows 1-255; we cap our own value at 64).
-- provider_request_id VARCHAR(39): Xendit payment_request_id is a fixed-length 39-char id
--   ("pr-<uuid>"). Nullable: unknown until the provider response arrives (transient TX1 state).
-- qr_payload TEXT: raw QRIS EMVCo QR string returned by Xendit. TEXT because the exact
--   QRIS Indonesia response shape/length is UNPROVEN until the owner sandbox smoke test.
ALTER TABLE payments
    ADD COLUMN provider_reference_id VARCHAR(64),
    ADD COLUMN provider_request_id VARCHAR(39),
    ADD COLUMN qr_payload TEXT;

-- 2. Drop inline method check and replace with named constraint
ALTER TABLE payments
    DROP CONSTRAINT payments_method_check;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_method
    CHECK (method IN ('CASH', 'CARD_EDC', 'QRIS_MIDTRANS', 'QRIS'));

-- 3. Extend provider check to include XENDIT
ALTER TABLE payments
    DROP CONSTRAINT ck_payments_provider;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_provider
    CHECK (provider IS NULL OR provider IN ('MIDTRANS', 'XENDIT'));

-- 4. Replace ck_payments_qris_provider with mutually exclusive branches matching Truth Table
ALTER TABLE payments
    DROP CONSTRAINT ck_payments_qris_provider;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_qris_provider CHECK (
        -- Branch 1: Non-provider offline payments (CASH, CARD_EDC)
        (
            (method IN ('CASH', 'CARD_EDC'))
            AND (provider IS NULL)
            AND (provider_order_id IS NULL)
            AND (provider_reference_id IS NULL)
            AND (provider_request_id IS NULL)
            AND (provider_transaction_id IS NULL)
            AND (provider_idempotency_key IS NULL)
            AND (charge_state IS NULL)
            AND (charge_started_at IS NULL)
            AND (charge_attempts = 0)
            AND (qr_payload IS NULL)
            AND (expires_at IS NULL)
        )
        OR
        -- Branch 2: Midtrans QRIS (Preserves EXACT V8 Invariants for Historical & Drained Data)
        (
            (method = 'QRIS_MIDTRANS')
            AND (provider IS NOT NULL AND provider = 'MIDTRANS')
            AND (provider_order_id IS NOT NULL)
            AND (provider_reference_id IS NULL)
            AND (provider_request_id IS NULL)
            AND (provider_idempotency_key IS NOT NULL)
            AND (charge_state IS NOT NULL)
            AND (charge_state IN ('NOT_SENT', 'SENDING', 'ACCEPTED', 'REJECTED'))
            AND (qr_payload IS NULL)
        )
        OR
        -- Branch 3: Xendit QRIS (M3-A/V9 spec: identity columns only).
        -- OPEN_QUESTION (PM/M4): is provider_transaction_id (Xendit payment_id) used by M4?
        --   Deliberately unrestricted here (NULL or filled) so M4 is never blocked.
        (
            (method = 'QRIS')
            AND (provider IS NOT NULL AND provider = 'XENDIT')
            AND (provider_order_id IS NULL)
            AND (provider_reference_id IS NOT NULL)
            AND (provider_idempotency_key IS NULL)
            AND (charge_state IS NOT NULL)
            AND (charge_state IN ('NOT_SENT', 'SENDING', 'ACCEPTED', 'REJECTED', 'AMBIGUOUS'))
        )
    );

-- 5. Row-lifetime charge_state x status coupling for Xendit rows only.
--    NULL-provider rows (CASH/CARD_EDC) and legacy Midtrans rows pass via IS DISTINCT FROM.
--    AMBIGUOUS may settle to PAID only in M4 after authenticated and correlated
--    provider evidence; the application must enforce that evidence requirement.
--    OPEN_QUESTION (PM): status REFUNDED is outside the allowed set; M4/M6 refund lifecycle
--    would need this constraint revisited.
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_qris_state_status CHECK (
        (provider IS DISTINCT FROM 'XENDIT')
        OR (
            (charge_state = 'REJECTED' AND status = 'FAILED')
            OR (charge_state IN ('NOT_SENT', 'SENDING') AND status = 'PENDING')
            OR (charge_state IN ('ACCEPTED', 'AMBIGUOUS')
                AND status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CANCELLED'))
        )
    );

-- 6. Add partial unique constraints for Xendit identifiers
--    (partial: multiple NULLs never collide)
CREATE UNIQUE INDEX uq_payments_provider_reference_id
    ON payments (provider_reference_id)
    WHERE provider_reference_id IS NOT NULL;

CREATE UNIQUE INDEX uq_payments_xendit_provider_request_id
    ON payments (provider_request_id)
    WHERE provider_request_id IS NOT NULL;
