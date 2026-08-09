-- V2: Adds authorized_count column and expands deal_slot_requests operation types.
-- See design doc §3 — authorized_count tracks payment-verified joins; deal success/failure
-- is now based on this counter, not current_participants.

ALTER TABLE deals
    ADD COLUMN authorized_count INT NOT NULL DEFAULT 0 CHECK (authorized_count >= 0);

-- authorized_count can never exceed current_participants (can't have more verified payers than joiners)
ALTER TABLE deals
    ADD CONSTRAINT chk_authorized_le_current_participants CHECK (authorized_count <= current_participants);

-- Expand operation types on deal_slot_requests to cover authorize-slot and release-authorized-slot.
-- Drop old CHECK, add new one.
ALTER TABLE deal_slot_requests
    DROP CONSTRAINT IF EXISTS deal_slot_requests_operation_check;

ALTER TABLE deal_slot_requests
    ADD CONSTRAINT deal_slot_requests_operation_check
        CHECK (operation IN ('RESERVE', 'RELEASE', 'AUTHORIZE', 'RELEASE_AUTHORIZED'));
