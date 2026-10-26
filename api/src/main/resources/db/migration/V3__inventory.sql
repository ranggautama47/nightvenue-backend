CREATE TABLE stock_locations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('WAREHOUSE', 'BAR')),
    CONSTRAINT uq_stock_locations_venue_name UNIQUE (venue_id, name)
);

CREATE UNIQUE INDEX uq_one_bar_per_venue ON stock_locations (venue_id) WHERE type = 'BAR';

CREATE TABLE inventory_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id BIGINT NOT NULL REFERENCES stock_locations(id) ON DELETE RESTRICT,
    ingredient_id BIGINT NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity NUMERIC(14,3) NOT NULL CONSTRAINT ck_inventory_qty_nonneg CHECK (quantity >= 0),
    reorder_level NUMERIC(14,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_inventory_location_ingredient UNIQUE (location_id, ingredient_id)
);

CREATE TABLE inventory_transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inventory_item_id BIGINT NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
    type VARCHAR(20) NOT NULL CHECK (type IN ('PURCHASE_IN', 'SALE_DEDUCT', 'VOID_RETURN', 'WASTE', 'ADJUSTMENT')),
    delta NUMERIC(14,3) NOT NULL,
    balance_after NUMERIC(14,3) NOT NULL CHECK (balance_after >= 0),
    business_date DATE NOT NULL,
    reference_type VARCHAR(20) CHECK (reference_type IN ('ORDER_ITEM', 'VOID_LOG', 'MANUAL', 'SEED')),
    reference_id BIGINT,
    reason VARCHAR(255),
    created_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inventory_transaction_reason CHECK (type NOT IN ('WASTE', 'ADJUSTMENT') OR reason IS NOT NULL)
);

CREATE INDEX ix_inventory_transactions_item_created ON inventory_transactions (inventory_item_id, created_at);
CREATE INDEX ix_inventory_transactions_business_date ON inventory_transactions (business_date);
