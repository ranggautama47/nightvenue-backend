CREATE TABLE void_logs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
  order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
  order_item_id BIGINT NOT NULL REFERENCES order_items(id) ON DELETE RESTRICT,
  batch_id UUID NOT NULL,
  voided_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  approved_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
  reason VARCHAR(255) NOT NULL,
  disposition VARCHAR(10) NOT NULL CONSTRAINT ck_void_logs_disposition CHECK (disposition IN ('RESTORED','WASTED')),
  item_value BIGINT NOT NULL CHECK (item_value >= 0),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_void_once_per_item UNIQUE (order_item_id)
);
CREATE INDEX ix_void_logs_order_id ON void_logs (order_id);
CREATE INDEX ix_inventory_transactions_ref ON inventory_transactions (reference_type, reference_id);
