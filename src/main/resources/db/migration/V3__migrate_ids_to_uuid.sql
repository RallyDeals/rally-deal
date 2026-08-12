-- V3: Migrate all identity columns from BIGINT/BIGSERIAL to UUID (design doc §3).
-- UUID v7 is time-sortable, so using gen_random_uuid() as a DB-level default for
-- any inserts that don't supply their own id. Application layer generates UUID v7
-- via the uuid-creator library for proper time-ordering.

-- Enable pgcrypto for gen_random_uuid() on older PG versions (PG13+ has it built in)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ────────────────────────────────────────────────────────
-- 1. Drop all foreign keys referencing deals.id
-- ────────────────────────────────────────────────────────
ALTER TABLE deal_outbox DROP CONSTRAINT IF EXISTS deal_outbox_deal_id_fkey;
ALTER TABLE deal_slot_requests DROP CONSTRAINT IF EXISTS deal_slot_requests_deal_id_fkey;

-- ────────────────────────────────────────────────────────
-- 2. Migrate deals table
-- ────────────────────────────────────────────────────────
-- Add new UUID columns
ALTER TABLE deals ADD COLUMN id_new UUID DEFAULT gen_random_uuid();
ALTER TABLE deals ADD COLUMN product_id_new UUID;
ALTER TABLE deals ADD COLUMN seller_id_new UUID;

-- Populate with generated UUIDs for any existing rows
UPDATE deals SET
    id_new = gen_random_uuid(),
    product_id_new = gen_random_uuid(),
    seller_id_new = gen_random_uuid()
WHERE id_new IS NULL OR product_id_new IS NULL OR seller_id_new IS NULL;

-- Update child tables to reference the new UUID before dropping old columns
ALTER TABLE deal_outbox ADD COLUMN deal_id_new UUID;
UPDATE deal_outbox SET deal_id_new = d.id_new
FROM deals d WHERE deal_outbox.deal_id = d.id;

ALTER TABLE deal_slot_requests ADD COLUMN deal_id_new UUID;
UPDATE deal_slot_requests SET deal_id_new = d.id_new
FROM deals d WHERE deal_slot_requests.deal_id = d.id;

-- Drop old columns and rename new ones on deals
ALTER TABLE deals DROP CONSTRAINT deals_pkey;
ALTER TABLE deals DROP COLUMN id;
ALTER TABLE deals RENAME COLUMN id_new TO id;
ALTER TABLE deals ADD PRIMARY KEY (id);

ALTER TABLE deals DROP COLUMN product_id;
ALTER TABLE deals RENAME COLUMN product_id_new TO product_id;
ALTER TABLE deals ALTER COLUMN product_id SET NOT NULL;

ALTER TABLE deals DROP COLUMN seller_id;
ALTER TABLE deals RENAME COLUMN seller_id_new TO seller_id;
ALTER TABLE deals ALTER COLUMN seller_id SET NOT NULL;

-- ────────────────────────────────────────────────────────
-- 3. Migrate deal_outbox table
-- ────────────────────────────────────────────────────────
ALTER TABLE deal_outbox DROP COLUMN deal_id;
ALTER TABLE deal_outbox RENAME COLUMN deal_id_new TO deal_id;
ALTER TABLE deal_outbox ALTER COLUMN deal_id SET NOT NULL;

-- Also migrate outbox id from BIGSERIAL to UUID
ALTER TABLE deal_outbox DROP CONSTRAINT deal_outbox_pkey;
ALTER TABLE deal_outbox DROP COLUMN id;
ALTER TABLE deal_outbox ADD COLUMN id UUID DEFAULT gen_random_uuid() NOT NULL;
ALTER TABLE deal_outbox ADD PRIMARY KEY (id);

-- ────────────────────────────────────────────────────────
-- 4. Migrate deal_slot_requests table
-- ────────────────────────────────────────────────────────
ALTER TABLE deal_slot_requests DROP COLUMN deal_id;
ALTER TABLE deal_slot_requests RENAME COLUMN deal_id_new TO deal_id;
ALTER TABLE deal_slot_requests ALTER COLUMN deal_id SET NOT NULL;

-- ────────────────────────────────────────────────────────
-- 5. Re-add foreign keys with UUID columns
-- ────────────────────────────────────────────────────────
ALTER TABLE deal_outbox ADD CONSTRAINT deal_outbox_deal_id_fkey
    FOREIGN KEY (deal_id) REFERENCES deals(id);

ALTER TABLE deal_slot_requests ADD CONSTRAINT deal_slot_requests_deal_id_fkey
    FOREIGN KEY (deal_id) REFERENCES deals(id);

-- ────────────────────────────────────────────────────────
-- 6. Re-create indexes that were dropped with column changes
-- ────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_deals_seller_id;
CREATE INDEX idx_deals_seller_id ON deals (seller_id, status);

DROP INDEX IF EXISTS idx_deals_product_id;
CREATE INDEX idx_deals_product_id ON deals (product_id);

DROP INDEX IF EXISTS idx_deal_slot_requests_deal_id;
CREATE INDEX idx_deal_slot_requests_deal_id ON deal_slot_requests (deal_id);
