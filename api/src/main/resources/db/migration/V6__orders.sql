CREATE SEQUENCE order_number_seq;

CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    order_number VARCHAR(30) NOT NULL,
    table_id BIGINT REFERENCES venue_tables(id) ON DELETE RESTRICT,
    customer_id BIGINT REFERENCES customers(id) ON DELETE RESTRICT,
    cashier_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    business_date DATE NOT NULL,
    status VARCHAR(15) NOT NULL CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'VOIDED', 'REFUNDED')),
    subtotal BIGINT NOT NULL CHECK (subtotal >= 0),
    discount_amount BIGINT NOT NULL DEFAULT 0,
    service_charge_amount BIGINT NOT NULL DEFAULT 0,
    tax_amount BIGINT NOT NULL DEFAULT 0,
    total_due BIGINT NOT NULL CHECK (total_due >= 0),
    paid_total BIGINT NOT NULL DEFAULT 0,
    minimum_spend_snapshot BIGINT NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_orders_venue_number UNIQUE (venue_id, order_number)
);

CREATE UNIQUE INDEX uq_orders_active_table
    ON orders (table_id)
    WHERE table_id IS NOT NULL
      AND status IN ('OPEN', 'PARTIALLY_PAID');

CREATE INDEX ix_orders_venue_business_date_status
    ON orders (venue_id, business_date, status);

CREATE TABLE order_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    item_type VARCHAR(20) NOT NULL CHECK (item_type IN ('PRODUCT', 'BOTTLE_REDEMPTION')),
    product_name_snapshot VARCHAR(150) NOT NULL,
    unit_price_snapshot BIGINT NOT NULL CHECK (unit_price_snapshot >= 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total BIGINT NOT NULL CHECK (line_total >= 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ORDERED', 'SERVED', 'VOIDED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_order_items_order_id ON order_items (order_id);

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_scope_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_scope_check
    CHECK (scope IN ('ORDER_ADD_ITEMS', 'PAYMENT_CREATE', 'ORDER_VOID', 'BOTTLE_DEPOSIT', 'BOTTLE_REDEEM', 'ORDER_OPEN'));
