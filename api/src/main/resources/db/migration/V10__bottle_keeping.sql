CREATE TABLE bottle_vaults (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    customer_id BIGINT NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    source_order_item_id BIGINT REFERENCES order_items(id) ON DELETE RESTRICT,
    label_code VARCHAR(20) NOT NULL,
    qr_token_hash CHAR(64) NOT NULL,
    original_volume_ml INT NOT NULL,
    remaining_volume_ml INT NOT NULL,
    stored_at TIMESTAMPTZ NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_vault_source_item UNIQUE (source_order_item_id),
    CONSTRAINT uq_bottle_vaults_label_code UNIQUE (label_code),
    CONSTRAINT uq_bottle_vaults_qr_token_hash UNIQUE (qr_token_hash),
    CONSTRAINT ck_bottle_vaults_original_volume CHECK (original_volume_ml > 0),
    CONSTRAINT ck_vault_volume CHECK (remaining_volume_ml >= 0 AND remaining_volume_ml <= original_volume_ml),
    CONSTRAINT ck_bottle_vaults_status CHECK (status IN ('ACTIVE', 'EMPTY', 'EXPIRED', 'RELEASED'))
);

CREATE INDEX ix_bottle_vaults_customer_status ON bottle_vaults (customer_id, status);
CREATE INDEX ix_bottle_vaults_expiry_active ON bottle_vaults (expiry_date) WHERE status = 'ACTIVE';

CREATE TABLE bottle_vault_transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vault_id BIGINT NOT NULL REFERENCES bottle_vaults(id) ON DELETE RESTRICT,
    type VARCHAR(10) NOT NULL,
    volume_delta_ml INT NOT NULL,
    remaining_after_ml INT NOT NULL,
    order_item_id BIGINT REFERENCES order_items(id) ON DELETE RESTRICT,
    owner_confirmed BOOLEAN NOT NULL DEFAULT false,
    performed_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    note VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_bottle_vault_transactions_type CHECK (type IN ('DEPOSIT', 'REDEEM', 'RELEASE', 'EXPIRE', 'ADJUST')),
    CONSTRAINT ck_bottle_vault_transactions_remaining_after CHECK (remaining_after_ml >= 0),
    CONSTRAINT ck_bv_tx_actor CHECK ((type = 'EXPIRE' AND performed_by IS NULL) OR (type <> 'EXPIRE' AND performed_by IS NOT NULL)),
    CONSTRAINT ck_bv_tx_redeem_confirmed CHECK (type <> 'REDEEM' OR owner_confirmed IS TRUE)
);

