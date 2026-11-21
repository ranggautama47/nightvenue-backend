CREATE TABLE venue_faqs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    intent VARCHAR(40) NOT NULL,
    answer_template TEXT NOT NULL,
    is_active BOOLEAN NOT NULL,
    CONSTRAINT uq_venue_faqs_venue_intent UNIQUE (venue_id, intent)
);

CREATE TABLE promotions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    is_active BOOLEAN NOT NULL,
    CONSTRAINT ck_promotions_date_range CHECK (ends_on >= starts_on)
);

CREATE TABLE chat_route_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT REFERENCES venues(id) ON DELETE SET NULL,
    route VARCHAR(25) NOT NULL,
    intent VARCHAR(40),
    confidence NUMERIC(5,4),
    ml_available BOOLEAN NOT NULL,
    classifier_ms INTEGER,
    llm_provider VARCHAR(10),
    llm_ms INTEGER,
    input_tokens INTEGER,
    output_tokens INTEGER,
    estimated_cost_usd NUMERIC(12,6),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_chat_route_logs_route CHECK (route IN ('LOCAL', 'FALLBACK', 'LLM', 'REFUSED', 'ADVANCED_AI_REQUIRED')),
    CONSTRAINT ck_chat_route_logs_llm_provider CHECK (llm_provider IS NULL OR llm_provider IN ('GEMINI', 'GROQ'))
);

CREATE INDEX ix_chat_route_logs_created_at ON chat_route_logs (created_at);
CREATE INDEX ix_chat_route_logs_route ON chat_route_logs (route);
