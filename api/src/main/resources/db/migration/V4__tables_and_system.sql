CREATE TABLE venue_tables (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    code VARCHAR(20) NOT NULL,
    area VARCHAR(50),
    capacity INT NOT NULL CHECK (capacity > 0),
    minimum_spend BIGINT NOT NULL DEFAULT 0 CHECK (minimum_spend >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'OUT_OF_SERVICE')),
    is_active BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_venue_tables_venue_code UNIQUE (venue_id, code)
);

CREATE TABLE audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT REFERENCES venues(id) ON DELETE SET NULL,
    actor_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT,
    details JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_created_at ON audit_logs (created_at);

CREATE TABLE idempotency_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key UUID NOT NULL,
    scope VARCHAR(40) NOT NULL CHECK (scope IN ('ORDER_ADD_ITEMS', 'PAYMENT_CREATE', 'ORDER_VOID', 'BOTTLE_DEPOSIT', 'BOTTLE_REDEEM')),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_hash CHAR(64) NOT NULL,
    response_status INT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idem UNIQUE (scope, user_id, key)
);
