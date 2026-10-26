CREATE TABLE venues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255),
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Jakarta',
    business_day_cutoff_hour SMALLINT NOT NULL DEFAULT 6 CHECK (business_day_cutoff_hour BETWEEN 0 AND 23),
    tax_rate_bps INT NOT NULL DEFAULT 0 CHECK (tax_rate_bps BETWEEN 0 AND 10000),
    service_charge_bps INT NOT NULL DEFAULT 0 CHECK (service_charge_bps BETWEEN 0 AND 10000),
    void_manager_approval_threshold BIGINT NOT NULL DEFAULT 500000 CHECK (void_manager_approval_threshold >= 0),
    opening_hours JSONB,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'CASHIER', 'CUSTOMER')),
    is_active BOOLEAN NOT NULL DEFAULT true,
    last_login_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_venues (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, venue_id)
);

CREATE TABLE customers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT UNIQUE REFERENCES users(id) ON DELETE SET NULL,
    full_name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) UNIQUE,
    email VARCHAR(255),
    age_verified BOOLEAN NOT NULL DEFAULT false,
    age_verified_at TIMESTAMPTZ,
    age_verified_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_customers_age_verified CHECK (NOT age_verified OR (age_verified_at IS NOT NULL AND age_verified_by IS NOT NULL))
);
