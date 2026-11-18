ALTER TABLE cashier_shifts
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN expected_cash BIGINT,
    ADD COLUMN actual_cash BIGINT,
    ADD COLUMN variance BIGINT,
    ADD COLUMN submit_note VARCHAR(255),
    ADD COLUMN reviewed_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN review_note VARCHAR(255),
    ADD CONSTRAINT ck_cashier_shifts_reconciliation_values CHECK (
        (expected_cash IS NULL AND actual_cash IS NULL AND variance IS NULL)
        OR (expected_cash IS NOT NULL AND actual_cash IS NOT NULL AND variance IS NOT NULL
            AND variance = actual_cash - expected_cash)
    ),
    ADD CONSTRAINT ck_cashier_shifts_expected_cash_nonnegative CHECK (
        expected_cash IS NULL OR expected_cash >= 0
    ),
    ADD CONSTRAINT ck_cashier_shifts_actual_cash_nonnegative CHECK (
        actual_cash IS NULL OR actual_cash >= 0
    ),
    ADD CONSTRAINT ck_cashier_shifts_reviewed_by_not_owner CHECK (
        reviewed_by IS NULL OR reviewed_by <> cashier_id
    ),
    ADD CONSTRAINT ck_cashier_shifts_review_fields_only_when_reconciled CHECK (
        status = 'RECONCILED'
        OR (reviewed_by IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
    ),
    ADD CONSTRAINT ck_cashier_shifts_pre_review_clean CHECK (
        status NOT IN ('OPEN','MANAGER_REVIEW')
        OR (submitted_at IS NULL AND expected_cash IS NULL AND actual_cash IS NULL
            AND variance IS NULL AND submit_note IS NULL
            AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
    ),
    ADD CONSTRAINT ck_cashier_shifts_reconciled CHECK (
        status <> 'RECONCILED'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND expected_cash IS NOT NULL AND actual_cash IS NOT NULL AND variance IS NOT NULL)
    );

CREATE INDEX ix_payments_shift_id ON payments (shift_id);

CREATE INDEX ix_cashier_shifts_open_venue_business_date
    ON cashier_shifts (venue_id, business_date)
    WHERE status = 'OPEN';
