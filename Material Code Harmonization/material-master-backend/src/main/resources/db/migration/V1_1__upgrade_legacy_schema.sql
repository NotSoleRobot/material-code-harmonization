-- Upgrade databases created by the pre-Flyway Hibernate schema to the V1 model.
-- A legacy database may already be baselined at version 1, so this migration is
-- deliberately idempotent and runs before V2 reference-data seeding.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE material_category
    ADD COLUMN IF NOT EXISTS code_segment VARCHAR(10),
    ADD COLUMN IF NOT EXISTS code_family VARCHAR(10),
    ADD COLUMN IF NOT EXISTS code_class VARCHAR(10);

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE "user" DROP CONSTRAINT IF EXISTS chk_operator_has_cpse;
ALTER TABLE "user" DROP CONSTRAINT IF EXISTS chk_user_cpse;
ALTER TABLE "user"
    ADD CONSTRAINT chk_user_cpse CHECK (
        (role = 'OPERATOR' AND cpse_id IS NOT NULL)
        OR role IN ('REVIEWER', 'SENIOR_REVIEWER')
        OR (role = 'ADMIN' AND cpse_id IS NULL)
    );

ALTER TABLE material
    ADD COLUMN IF NOT EXISTS nominal_price NUMERIC(14,2);

ALTER TABLE material_group
    ADD COLUMN IF NOT EXISTS attribute_signature VARCHAR(64),
    ADD COLUMN IF NOT EXISTS signature_attributes TEXT,
    ADD COLUMN IF NOT EXISTS signature_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS provisional_ref VARCHAR(50),
    ADD COLUMN IF NOT EXISTS superseded_by_group_id BIGINT;

UPDATE material_group
SET attribute_signature = UPPER(
        md5('legacy-material-group:' || group_id::text)
        || md5('legacy-material-group-signature:' || group_id::text)
    )
WHERE attribute_signature IS NULL;

UPDATE material_group
SET provisional_ref = 'LEGACY-' || LPAD(group_id::text, 10, '0')
WHERE provisional_ref IS NULL;

ALTER TABLE material_group
    ALTER COLUMN attribute_signature SET NOT NULL,
    ALTER COLUMN provisional_ref SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_material_group_attribute_signature') THEN
        ALTER TABLE material_group
            ADD CONSTRAINT uq_material_group_attribute_signature UNIQUE (attribute_signature);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_material_group_provisional_ref') THEN
        ALTER TABLE material_group
            ADD CONSTRAINT uq_material_group_provisional_ref UNIQUE (provisional_ref);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_material_group_superseded_by') THEN
        ALTER TABLE material_group
            ADD CONSTRAINT fk_material_group_superseded_by
            FOREIGN KEY (superseded_by_group_id) REFERENCES material_group(group_id);
    END IF;
END $$;

ALTER TABLE material_mapping
    ADD COLUMN IF NOT EXISTS confidence_tier VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN IF NOT EXISTS explanation_json TEXT,
    ADD COLUMN IF NOT EXISTS decision_notes TEXT;

UPDATE material_mapping
SET confidence_tier = CASE
    WHEN confidence_score >= 0.85 THEN 'HIGH'
    WHEN confidence_score >= 0.60 THEN 'MEDIUM'
    ELSE 'LOW'
END;

CREATE TABLE IF NOT EXISTS reviewer_assignment (
    assignment_id BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES "user"(user_id) ON DELETE CASCADE,
    category_id   BIGINT NOT NULL REFERENCES material_category(category_id) ON DELETE CASCADE,
    created_at    TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_reviewer_category UNIQUE (user_id, category_id)
);

CREATE TABLE IF NOT EXISTS group_relation (
    relation_id     BIGSERIAL PRIMARY KEY,
    group_a_id      BIGINT NOT NULL REFERENCES material_group(group_id) ON DELETE CASCADE,
    group_b_id      BIGINT NOT NULL REFERENCES material_group(group_id) ON DELETE CASCADE,
    relation_type   VARCHAR(30) NOT NULL,
    confidence      NUMERIC(5,4),
    approved_by     BIGINT REFERENCES "user"(user_id),
    approved_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_group_relation UNIQUE (group_a_id, group_b_id, relation_type)
);

CREATE TABLE IF NOT EXISTS harmonization_job (
    job_id              BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES "user"(user_id),
    cpse_id             BIGINT REFERENCES cpse(cpse_id),
    total_items         INT NOT NULL DEFAULT 0,
    processed_items     INT NOT NULL DEFAULT 0,
    auto_harmonized     INT NOT NULL DEFAULT 0,
    pending_review      INT NOT NULL DEFAULT 0,
    distinct_materials  INT NOT NULL DEFAULT 0,
    status              VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    error_message       TEXT,
    created_at          TIMESTAMP DEFAULT now(),
    completed_at        TIMESTAMP
);

CREATE TABLE IF NOT EXISTS procurement_assumption (
    assumption_id   BIGSERIAL PRIMARY KEY,
    key             VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    value           NUMERIC(14,2) NOT NULL,
    unit            VARCHAR(30) NOT NULL,
    updated_by      BIGINT REFERENCES "user"(user_id),
    updated_at      TIMESTAMP DEFAULT now()
);

ALTER TABLE audit_trail
    ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS row_hash VARCHAR(64);

-- Backfill the legacy audit records using the exact timestamp representation
-- produced by Java LocalDateTime.toString(), preserving a verifiable chain.
DO $$
DECLARE
    audit_row RECORD;
    previous_hash TEXT := repeat('0', 64);
    current_hash TEXT;
    timestamp_text TEXT;
    micros TEXT;
BEGIN
    FOR audit_row IN SELECT * FROM audit_trail ORDER BY audit_id LOOP
        micros := to_char(audit_row.timestamp, 'US');
        timestamp_text := to_char(audit_row.timestamp, 'YYYY-MM-DD"T"HH24:MI:SS') ||
            CASE
                WHEN micros = '000000' THEN ''
                WHEN right(micros, 3) = '000' THEN '.' || left(micros, 3)
                ELSE '.' || micros
            END;

        current_hash := UPPER(encode(digest(
            convert_to(
                previous_hash || '|' || COALESCE(audit_row.user_id, 0)::text || '|' ||
                audit_row.action || '|' || audit_row.entity_type || '|' ||
                audit_row.entity_id::text || '|' || timestamp_text || '|' ||
                COALESCE(audit_row.new_value, ''),
                'UTF8'
            ),
            'sha256'
        ), 'hex'));

        UPDATE audit_trail
        SET prev_hash = previous_hash, row_hash = current_hash
        WHERE audit_id = audit_row.audit_id;

        previous_hash := current_hash;
    END LOOP;
END $$;

ALTER TABLE audit_trail ALTER COLUMN row_hash SET NOT NULL;

CREATE SEQUENCE IF NOT EXISTS numm_serial_seq START WITH 1 INCREMENT BY 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_mapping_per_material
    ON material_mapping (material_id)
    WHERE status != 'REJECTED';
CREATE INDEX IF NOT EXISTS idx_material_cpse ON material(cpse_id);
CREATE INDEX IF NOT EXISTS idx_material_category ON material(category_id);
CREATE INDEX IF NOT EXISTS idx_material_mapping_status ON material_mapping(status);
CREATE INDEX IF NOT EXISTS idx_material_mapping_group ON material_mapping(group_id);
CREATE INDEX IF NOT EXISTS idx_material_mapping_tier ON material_mapping(confidence_tier);
CREATE INDEX IF NOT EXISTS idx_group_signature ON material_group(attribute_signature);
CREATE INDEX IF NOT EXISTS idx_group_status ON material_group(status);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_trail(timestamp DESC);
