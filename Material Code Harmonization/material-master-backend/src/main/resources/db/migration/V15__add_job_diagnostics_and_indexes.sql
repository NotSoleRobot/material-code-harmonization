-- V15__add_job_diagnostics_and_indexes.sql
-- Add diagnostics payload to harmonization_job and high-scale search indexes

ALTER TABLE harmonization_job
    ADD COLUMN IF NOT EXISTS diagnostics TEXT;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_material_description_trgm
    ON material USING gin (lower(description) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_material_specification_trgm
    ON material USING gin (lower(specification) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_material_code_lower
    ON material (lower(cpse_material_code));

CREATE INDEX IF NOT EXISTS idx_job_status_created
    ON harmonization_job (status, created_at DESC);
