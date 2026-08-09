-- Deal Service schema — see design doc §3.
--
-- DEVIATION FROM THE ORIGINAL DESIGN DOC, FLAGGED DELIBERATELY:
-- The design doc used a native Postgres `deal_status` ENUM TYPE. This migration uses
-- VARCHAR + CHECK instead. Native Postgres enums are notoriously fragile to map from
-- Hibernate (case-sensitivity between Java enum constant names and Postgres enum labels,
-- plus extra @JdbcTypeCode wiring), and give no functional benefit over VARCHAR+CHECK for
-- a 5-value, rarely-changing set. Same guarantee (illegal values rejected at the DB),
-- much simpler ORM mapping. Worth reverting to a native enum later if you want the
-- marginal storage/readability benefit — not worth the friction during initial build-out.

CREATE TABLE deals (
    id                    BIGSERIAL PRIMARY KEY,
    product_id            BIGINT NOT NULL,
    seller_id             BIGINT NOT NULL,

    original_price        NUMERIC(10,2) NOT NULL CHECK (original_price > 0),
    deal_price            NUMERIC(10,2) NOT NULL CHECK (deal_price > 0),
    deal_stock            INT NOT NULL CHECK (deal_stock > 0),
    current_participants  INT NOT NULL DEFAULT 0 CHECK (current_participants >= 0),
    min_participants      INT NOT NULL CHECK (min_participants > 0),

    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'ACTIVE', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    start_time            TIMESTAMPTZ,
    duration_minutes      INT NOT NULL CHECK (duration_minutes > 0),
    end_time              TIMESTAMPTZ,

    version               INT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_deal_price_lt_original_price CHECK (deal_price < original_price),
    CONSTRAINT chk_min_participants_le_deal_stock CHECK (min_participants <= deal_stock),
    CONSTRAINT chk_current_participants_le_deal_stock CHECK (current_participants <= deal_stock)
);

CREATE INDEX idx_deals_status          ON deals (status);
CREATE INDEX idx_deals_seller_id       ON deals (seller_id, status);
CREATE INDEX idx_deals_product_id      ON deals (product_id);
-- Powers the internal timer sweep (design doc §7):
CREATE INDEX idx_deals_active_end_time ON deals (end_time) WHERE status = 'ACTIVE';


CREATE TABLE deal_outbox (
    id              BIGSERIAL PRIMARY KEY,
    deal_id         BIGINT NOT NULL REFERENCES deals(id),
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_deal_outbox_unpublished ON deal_outbox (id) WHERE published_at IS NULL;


CREATE TABLE deal_slot_requests (
    request_id      UUID PRIMARY KEY,
    deal_id         BIGINT NOT NULL REFERENCES deals(id),
    operation       TEXT NOT NULL CHECK (operation IN ('RESERVE', 'RELEASE')),
    result          TEXT NOT NULL CHECK (result IN ('SUCCESS', 'REJECTED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_slot_requests_deal_id ON deal_slot_requests (deal_id);
