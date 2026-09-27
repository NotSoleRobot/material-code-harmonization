-- ============================================================================
-- V1__initial_schema.sql — National Unified Material Master (SIH 2026)
-- Complete core schema with governance, code generation, audit chaining,
-- multi-user auth, and performance indexes.
-- ============================================================================

-- 1. CPSEs (Public Sector Enterprises)
CREATE TABLE IF NOT EXISTS cpse (
    cpse_id     BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    sector      VARCHAR(100),
    created_at  TIMESTAMP DEFAULT now()
);

-- 2. Material Categories (UNSPSC-aligned 3-level hierarchy)
CREATE TABLE IF NOT EXISTS material_category (
    category_id     BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    level           INT NOT NULL, -- 1: Segment, 2: Family, 3: Class
    code_segment    VARCHAR(10),  -- e.g. "40" for Industrial Mfg, "31" for Components
    code_family     VARCHAR(10),  -- e.g. "14" for Piping
    code_class      VARCHAR(10),  -- e.g. "07" for Pipes, "16" for Valves
    parent_id       BIGINT REFERENCES material_category(category_id),
    description     TEXT
);

-- 3. Users (Multi-user auth with BCrypt hashes & CPSE / Reviewer scoping)
CREATE TABLE IF NOT EXISTS "user" (
    user_id        BIGSERIAL PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    email          VARCHAR(150) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(30) NOT NULL, -- OPERATOR | REVIEWER | SENIOR_REVIEWER | ADMIN
    cpse_id        BIGINT REFERENCES cpse(cpse_id),
    active         BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMP DEFAULT now(),

    -- Rule: OPERATOR must have cpse_id; REVIEWER/SENIOR_REVIEWER may have optional prior CPSE; ADMIN must not
    CONSTRAINT chk_user_cpse CHECK (
        (role = 'OPERATOR' AND cpse_id IS NOT NULL)
        OR (role IN ('REVIEWER', 'SENIOR_REVIEWER'))
        OR (role = 'ADMIN' AND cpse_id IS NULL)
    )
);

-- 4. Reviewer Commodity Assignments (W4.1 - Pipes specialist reviews pipes)
CREATE TABLE IF NOT EXISTS reviewer_assignment (
    assignment_id BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES "user"(user_id) ON DELETE CASCADE,
    category_id   BIGINT NOT NULL REFERENCES material_category(category_id) ON DELETE CASCADE,
    created_at    TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_reviewer_category UNIQUE (user_id, category_id)
);

-- 5. Raw Material Records (CPSE as-is master data)
CREATE TABLE IF NOT EXISTS material (
    material_id             BIGSERIAL PRIMARY KEY,
    cpse_id                 BIGINT NOT NULL REFERENCES cpse(cpse_id),
    cpse_material_code      VARCHAR(100) NOT NULL,
    description             TEXT NOT NULL,
    specification           TEXT,
    unit_of_measure         VARCHAR(30),
    category_id             BIGINT REFERENCES material_category(category_id),
    nominal_price           NUMERIC(14,2), -- for price-variance analytics
    extracted_dimension     VARCHAR(50),
    extracted_grade         VARCHAR(50),
    extracted_standard_code VARCHAR(50),
    extracted_material_type VARCHAR(50),
    created_at              TIMESTAMP DEFAULT now(),
    updated_at              TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_cpse_material_code UNIQUE (cpse_id, cpse_material_code)
);

-- 6. Unified Material Groups (Canonical National Records)
CREATE TABLE IF NOT EXISTS material_group (
    group_id                    BIGSERIAL PRIMARY KEY,
    attribute_signature         VARCHAR(64) UNIQUE NOT NULL, -- SHA-256 of canonical identity attrs
    signature_attributes        TEXT,                       -- JSON string of attrs that formed identity
    signature_version           INT NOT NULL DEFAULT 1,
    provisional_ref             VARCHAR(50) UNIQUE NOT NULL, -- e.g. PROV-2026-000001
    common_material_code        VARCHAR(50) UNIQUE,          -- NUMM-SS-FF-CC-NNNNNN-K (minted on approval)
    standardized_description    TEXT NOT NULL,
    standardized_specification  TEXT,
    standardized_uom            VARCHAR(30),
    category_id                 BIGINT REFERENCES material_category(category_id),
    status                      VARCHAR(30) NOT NULL DEFAULT 'PROPOSED', -- PROPOSED | ACTIVE | SUPERSEDED | DEPRECATED
    superseded_by_group_id      BIGINT REFERENCES material_group(group_id),
    created_at                  TIMESTAMP DEFAULT now(),
    updated_at                  TIMESTAMP DEFAULT now()
);

-- 7. Group Relations (W2.4 - Domain semantics for FUNCTIONALLY_EQUIVALENT & VARIANT)
CREATE TABLE IF NOT EXISTS group_relation (
    relation_id     BIGSERIAL PRIMARY KEY,
    group_a_id      BIGINT NOT NULL REFERENCES material_group(group_id) ON DELETE CASCADE,
    group_b_id      BIGINT NOT NULL REFERENCES material_group(group_id) ON DELETE CASCADE,
    relation_type   VARCHAR(30) NOT NULL, -- FUNCTIONALLY_EQUIVALENT | VARIANT
    confidence      NUMERIC(5,4),
    approved_by     BIGINT REFERENCES "user"(user_id),
    approved_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_group_relation UNIQUE (group_a_id, group_b_id, relation_type)
);

-- 8. Material Mapping (Link between messy CPSE material and Canonical Group)
CREATE TABLE IF NOT EXISTS material_mapping (
    mapping_id          BIGSERIAL PRIMARY KEY,
    material_id         BIGINT NOT NULL REFERENCES material(material_id),
    group_id            BIGINT NOT NULL REFERENCES material_group(group_id),
    confidence_score    NUMERIC(5,4) NOT NULL,
    confidence_tier     VARCHAR(10) NOT NULL DEFAULT 'MEDIUM', -- HIGH | MEDIUM | LOW
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING | CONFIRMED | REJECTED | SUPERSEDED
    explanation_json    TEXT,                                  -- Structured checks/warnings/conflicts
    reviewed_by         BIGINT REFERENCES "user"(user_id),
    reviewed_at         TIMESTAMP,
    decision_notes      TEXT,
    created_at          TIMESTAMP DEFAULT now()
);

-- Rule: Partial unique index — only one active (PENDING/CONFIRMED/SUPERSEDED) mapping per material
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_mapping_per_material
    ON material_mapping (material_id)
    WHERE status != 'REJECTED';

-- 9. Harmonization Jobs (W9.5 - Async bulk ingestion & progress tracking)
CREATE TABLE IF NOT EXISTS harmonization_job (
    job_id              BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES "user"(user_id),
    cpse_id             BIGINT REFERENCES cpse(cpse_id),
    total_items         INT NOT NULL DEFAULT 0,
    processed_items     INT NOT NULL DEFAULT 0,
    auto_harmonized     INT NOT NULL DEFAULT 0,
    pending_review      INT NOT NULL DEFAULT 0,
    distinct_materials  INT NOT NULL DEFAULT 0,
    status              VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS | COMPLETED | FAILED
    error_message       TEXT,
    created_at          TIMESTAMP DEFAULT now(),
    completed_at        TIMESTAMP
);

-- 10. Procurement Assumptions (W6.2 - Configurable ROI calculation assumptions)
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

-- 11. Tamper-Evident Audit Trail (W3.6 - Cryptographically chained log)
CREATE TABLE IF NOT EXISTS audit_trail (
    audit_id        BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES "user"(user_id),
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       BIGINT NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    prev_hash       VARCHAR(64), -- SHA-256 hash of the immediately preceding audit record
    row_hash        VARCHAR(64) NOT NULL, -- SHA-256(prev_hash + user_id + action + entity_type + entity_id + timestamp + new_value)
    timestamp       TIMESTAMP DEFAULT now()
);

-- Sequence for sequential National Material Code allocation per class
CREATE SEQUENCE IF NOT EXISTS numm_serial_seq START WITH 1 INCREMENT BY 1;

-- Optimized indexes for scale (W9.4)
CREATE INDEX IF NOT EXISTS idx_material_cpse ON material(cpse_id);
CREATE INDEX IF NOT EXISTS idx_material_category ON material(category_id);
CREATE INDEX IF NOT EXISTS idx_material_mapping_status ON material_mapping(status);
CREATE INDEX IF NOT EXISTS idx_material_mapping_group ON material_mapping(group_id);
CREATE INDEX IF NOT EXISTS idx_material_mapping_tier ON material_mapping(confidence_tier);
CREATE INDEX IF NOT EXISTS idx_group_signature ON material_group(attribute_signature);
CREATE INDEX IF NOT EXISTS idx_group_status ON material_group(status);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_trail(timestamp DESC);
