ALTER TABLE harmonization_job
    ADD COLUMN IF NOT EXISTS already_harmonized INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS queued_for_harmonization INT NOT NULL DEFAULT 0;

UPDATE "user"
SET active = false
WHERE role = 'REVIEWER';

DROP TABLE IF EXISTS procurement_assumption;

CREATE INDEX IF NOT EXISTS idx_mapping_material_status
    ON material_mapping (material_id, status);
