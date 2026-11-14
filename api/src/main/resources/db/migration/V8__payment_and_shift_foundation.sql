CREATE TABLE cashier_shifts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    cashier_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    business_date DATE NOT NULL,
    status VARCHAR(15) NOT NULL CHECK (status IN ('OPEN', 'SUBMITTED', 'MANAGER_REVIEW', 'RECONCILED')),
    opening_float BIGINT NOT NULL CHECK (opening_float >= 0),
    opened_at TIMESTAMPTZ NOT NULL,
    forced_review_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cashier_shifts_forced_review CHECK (status <> 'MANAGER_REVIEW' OR forced_review_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_cashier_shifts_one_open_per_cashier
    ON cashier_shifts (cashier_id)
    WHERE status = 'OPEN';

CREATE TABLE payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    business_date DATE NOT NULL,
    shift_id BIGINT REFERENCES cashier_shifts(id) ON DELETE RESTRICT,
    method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'CARD_EDC', 'QRIS_MIDTRANS')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    provider VARCHAR(20),
    provider_order_id VARCHAR(60),
    provider_transaction_id VARCHAR(80),
    provider_idempotency_key UUID,
    charge_state VARCHAR(12),
    charge_started_at TIMESTAMPTZ,
    charge_attempts INT NOT NULL DEFAULT 0 CHECK (charge_attempts >= 0),
    expires_at TIMESTAMPTZ,
    reference_note VARCHAR(100),
    paid_at TIMESTAMPTZ,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_provider_order_id UNIQUE (provider_order_id),
    CONSTRAINT uq_payments_provider_idempotency_key UNIQUE (provider_idempotency_key),
    CONSTRAINT ck_payments_paid_at CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CONSTRAINT ck_payments_shift_method CHECK (
        (method = 'CASH' AND shift_id IS NOT NULL)
        OR (method <> 'CASH' AND shift_id IS NULL)
    ),
    CONSTRAINT ck_payments_provider CHECK (provider IS NULL OR provider = 'MIDTRANS'),
    CONSTRAINT ck_payments_qris_provider CHECK (
        (method = 'QRIS_MIDTRANS' AND provider = 'MIDTRANS' AND provider_order_id IS NOT NULL
            AND provider_idempotency_key IS NOT NULL
            AND charge_state IS NOT NULL
            AND charge_state IN ('NOT_SENT', 'SENDING', 'ACCEPTED', 'REJECTED'))
        OR (method <> 'QRIS_MIDTRANS' AND provider IS NULL AND provider_order_id IS NULL
            AND provider_transaction_id IS NULL AND provider_idempotency_key IS NULL
            AND charge_state IS NULL AND charge_started_at IS NULL AND charge_attempts = 0
            AND expires_at IS NULL)
    ),
    CONSTRAINT ck_payments_charge_started CHECK (
        charge_state <> 'SENDING' OR charge_started_at IS NOT NULL
    )
);

CREATE INDEX ix_payments_order_status ON payments (order_id, status);

CREATE TABLE payment_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    provider VARCHAR(20) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    result VARCHAR(15) NOT NULL CHECK (result IN ('PROCESSED', 'IGNORED_STALE', 'REJECTED')),
    payload_snapshot JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_events_provider_event UNIQUE (provider, event_key)
);
