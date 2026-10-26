CREATE TABLE product_categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE products (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    category_id BIGINT REFERENCES product_categories(id) ON DELETE RESTRICT,
    sku VARCHAR(40) NOT NULL,
    name VARCHAR(150) NOT NULL,
    price BIGINT NOT NULL CHECK (price >= 0),
    is_bottle_keepable BOOLEAN NOT NULL DEFAULT false,
    bottle_volume_ml INT,
    is_active BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_products_venue_sku UNIQUE (venue_id, sku),
    CONSTRAINT ck_products_bottle_volume CHECK ((is_bottle_keepable AND bottle_volume_ml IS NOT NULL AND bottle_volume_ml > 0) OR (NOT is_bottle_keepable AND (bottle_volume_ml IS NULL OR bottle_volume_ml > 0)))
);

CREATE TABLE ingredients (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    unit VARCHAR(10) NOT NULL CHECK (unit IN ('ML', 'GRAM', 'PIECE')),
    is_active BOOLEAN NOT NULL
);

CREATE TABLE recipes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE REFERENCES products(id) ON DELETE CASCADE,
    version BIGINT,
    updated_at TIMESTAMPTZ
);

CREATE TABLE recipe_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipe_id BIGINT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    ingredient_id BIGINT NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    CONSTRAINT uq_recipe_items_recipe_ingredient UNIQUE (recipe_id, ingredient_id)
);
