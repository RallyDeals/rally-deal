-- V4: Add category_id column to deals table and create indexes for category lookups.

ALTER TABLE deals
    ADD COLUMN category_id UUID;

CREATE INDEX idx_deals_category_id ON deals (category_id);
CREATE INDEX idx_deals_category_status ON deals (category_id, status);
