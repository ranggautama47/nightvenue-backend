CREATE TABLE stock_forecasts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    ingredient_id BIGINT NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    generated_at TIMESTAMPTZ NOT NULL,
    as_of_business_date DATE NOT NULL,
    horizon_days INTEGER NOT NULL,
    predicted_quantity NUMERIC(14,3) NOT NULL,
    model_name VARCHAR(40) NOT NULL,
    model_mae NUMERIC(14,3),
    baseline_mae NUMERIC(14,3),
    input_days INTEGER NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_forecasts_horizon_positive CHECK (horizon_days > 0),
    CONSTRAINT ck_stock_forecasts_predicted_nonneg CHECK (predicted_quantity >= 0),
    CONSTRAINT ck_stock_forecasts_input_days_min CHECK (input_days >= 28)
);

CREATE INDEX ix_stock_forecasts_venue_ingredient ON stock_forecasts (venue_id, ingredient_id);
CREATE INDEX ix_stock_forecasts_as_of_date ON stock_forecasts (as_of_business_date);
