-- Score-driven harmonization routing and reproducible matching evidence.
ALTER TABLE material_mapping
    ADD COLUMN IF NOT EXISTS decision_source VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN IF NOT EXISTS routing_decision VARCHAR(30) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    ADD COLUMN IF NOT EXISTS model_version VARCHAR(80),
    ADD COLUMN IF NOT EXISTS second_best_score NUMERIC(5,4),
    ADD COLUMN IF NOT EXISTS candidate_margin NUMERIC(5,4),
    ADD COLUMN IF NOT EXISTS score_breakdown TEXT,
    ADD COLUMN IF NOT EXISTS critical_conflicts TEXT,
    ADD COLUMN IF NOT EXISTS automatically_decided_at TIMESTAMP;

UPDATE material_mapping
SET decision_source = CASE WHEN reviewed_by IS NOT NULL THEN 'HUMAN' ELSE 'LEGACY' END,
    routing_decision = CASE
        WHEN status = 'PENDING' THEN 'REVIEW_REQUIRED'
        WHEN status = 'CONFIRMED' THEN 'AUTO_CONFIRM'
        ELSE 'REVIEW_REQUIRED'
    END
WHERE decision_source = 'LEGACY';

CREATE TABLE IF NOT EXISTS matching_policy (
    policy_id BIGSERIAL PRIMARY KEY,
    category_id BIGINT NOT NULL UNIQUE REFERENCES material_category(category_id),
    auto_confirm_threshold NUMERIC(5,4) NOT NULL DEFAULT 0.9000,
    review_threshold NUMERIC(5,4) NOT NULL DEFAULT 0.7000,
    minimum_candidate_margin NUMERIC(5,4) NOT NULL DEFAULT 0.1000,
    auto_confirm_enabled BOOLEAN NOT NULL DEFAULT true,
    required_attributes TEXT,
    policy_version VARCHAR(40) NOT NULL DEFAULT '1.0',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_matching_policy_thresholds CHECK (
        review_threshold >= 0 AND auto_confirm_threshold <= 1
        AND review_threshold < auto_confirm_threshold
        AND minimum_candidate_margin >= 0 AND minimum_candidate_margin <= 1
    )
);

INSERT INTO matching_policy (category_id)
SELECT category_id FROM material_category WHERE level = 3
ON CONFLICT (category_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS matching_model_version (
    model_version VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    algorithm VARCHAR(120) NOT NULL,
    evaluation_metrics TEXT,
    trained_at TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO matching_model_version (model_version, display_name, algorithm, active)
VALUES ('hybrid-rf-1.0', 'Hybrid lexical and attribute random forest', 'TF-IDF + fuzzy + category attributes + random forest', true)
ON CONFLICT (model_version) DO NOTHING;

CREATE TABLE IF NOT EXISTS match_candidate (
    candidate_id BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL REFERENCES material(material_id) ON DELETE CASCADE,
    candidate_material_id BIGINT NOT NULL REFERENCES material(material_id) ON DELETE CASCADE,
    candidate_group_id BIGINT REFERENCES material_group(group_id),
    candidate_rank INT NOT NULL,
    predicted_relationship VARCHAR(40),
    match_score NUMERIC(5,4) NOT NULL,
    score_breakdown TEXT,
    critical_conflicts TEXT,
    model_version VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_match_candidate_run UNIQUE (material_id, candidate_material_id, model_version)
);

CREATE INDEX IF NOT EXISTS idx_match_candidate_material_rank
    ON match_candidate(material_id, candidate_rank);

CREATE TABLE IF NOT EXISTS matching_feedback (
    feedback_id BIGSERIAL PRIMARY KEY,
    mapping_id BIGINT NOT NULL REFERENCES material_mapping(mapping_id),
    material_id BIGINT NOT NULL REFERENCES material(material_id),
    candidate_group_id BIGINT REFERENCES material_group(group_id),
    reviewer_id BIGINT REFERENCES "user"(user_id),
    model_version VARCHAR(80),
    suggested_relationship VARCHAR(40),
    reviewer_decision VARCHAR(30) NOT NULL,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_matching_feedback_model
    ON matching_feedback(model_version, created_at);

CREATE TABLE IF NOT EXISTS material_synonym (
    synonym_id BIGSERIAL PRIMARY KEY,
    category_id BIGINT REFERENCES material_category(category_id),
    source_term VARCHAR(200) NOT NULL,
    canonical_term VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (category_id, source_term)
);

CREATE TABLE IF NOT EXISTS manufacturer_alias (
    alias_id BIGSERIAL PRIMARY KEY,
    alias VARCHAR(200) NOT NULL UNIQUE,
    canonical_name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS standard_equivalence (
    equivalence_id BIGSERIAL PRIMARY KEY,
    source_standard VARCHAR(120) NOT NULL,
    equivalent_standard VARCHAR(120) NOT NULL,
    category_id BIGINT REFERENCES material_category(category_id),
    evidence TEXT,
    active BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (source_standard, equivalent_standard, category_id)
);

CREATE TABLE IF NOT EXISTS unit_conversion (
    conversion_id BIGSERIAL PRIMARY KEY,
    source_unit VARCHAR(40) NOT NULL,
    target_unit VARCHAR(40) NOT NULL,
    multiplier NUMERIC(20,8) NOT NULL,
    offset_value NUMERIC(20,8) NOT NULL DEFAULT 0,
    UNIQUE (source_unit, target_unit)
);
