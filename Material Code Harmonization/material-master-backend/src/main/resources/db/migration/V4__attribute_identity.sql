-- V4__attribute_identity.sql
-- WP1: Fix the identity model (RC-1).
-- Adds extracted_attributes JSONB, fixes the broken unique constraint on
-- attribute_signature, and wires match_basis onto material_mapping.
-- Applied migrations V1, V1_1, V2, V3 are NOT touched.

-- 1. Add JSONB extracted_attributes + timestamp on material (D1 step 2)
ALTER TABLE material
    ADD COLUMN IF NOT EXISTS extracted_attributes     JSONB,
    ADD COLUMN IF NOT EXISTS attributes_extracted_at  TIMESTAMP;

-- 2. Add signature_complete flag on material_group (D1 step 4)
ALTER TABLE material_group
    ADD COLUMN IF NOT EXISTS signature_complete BOOLEAN NOT NULL DEFAULT false;

-- 3. Drop the broken UNIQUE NOT NULL constraint on attribute_signature.
--    Every category was collapsing into one group because the signature was
--    always SHA-256('{"__category":"PIPE"}') — a constant per category.
--    The column stays but is now nullable and the UNIQUE constraint is
--    replaced with a partial index that only fires when the signature
--    is actually populated (signature_complete = true).
ALTER TABLE material_group
    DROP CONSTRAINT IF EXISTS material_group_attribute_signature_key;

ALTER TABLE material_group
    ALTER COLUMN attribute_signature DROP NOT NULL;

-- Partial unique index: two groups can both have NULL signature
-- (incomplete attributes), but two complete signatures must be distinct.
CREATE UNIQUE INDEX IF NOT EXISTS uq_group_signature_complete
    ON material_group (attribute_signature)
    WHERE signature_complete = true AND attribute_signature IS NOT NULL;

-- 4. Add match_basis column to material_mapping (D1 step 6)
ALTER TABLE material_mapping
    ADD COLUMN IF NOT EXISTS match_basis VARCHAR(30);
-- Values: DETERMINISTIC_SIGNATURE | ML_PROPOSED | NOVEL

-- 5. Widen the partial unique index on material_mapping.
--    Old predicate: WHERE status != 'REJECTED'
--    This allowed two CONFIRMED rows for the same material (re-harmonize bug B-07).
--    New predicate: WHERE status IN ('PENDING','CONFIRMED')
--    SUPERSEDED rows are now excluded, so re-harmonization can insert a new
--    PENDING row after marking the old one SUPERSEDED.
ALTER TABLE material_mapping
    DROP CONSTRAINT IF EXISTS uq_active_mapping_per_material;

DROP INDEX IF EXISTS uq_active_mapping_per_material;

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_mapping_per_material
    ON material_mapping (material_id)
    WHERE status IN ('PENDING', 'CONFIRMED');
