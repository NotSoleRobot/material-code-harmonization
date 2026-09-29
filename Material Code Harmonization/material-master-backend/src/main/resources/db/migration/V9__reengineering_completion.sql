-- Production re-engineering completion: retrieval, code allocation, lineage and relations.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_material_description_trgm
    ON material USING gin (lower(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_material_dimension_lower
    ON material (lower(extracted_dimension));
CREATE INDEX IF NOT EXISTS idx_material_type_lower
    ON material (lower(extracted_material_type));

ALTER TABLE material_group ADD COLUMN IF NOT EXISTS code_serial BIGINT;

UPDATE material_group
SET code_serial = NULLIF(regexp_replace(provisional_ref, '^.*-', ''), '')::BIGINT
WHERE code_serial IS NULL
  AND provisional_ref ~ '[0-9]+$';

CREATE TABLE IF NOT EXISTS material_code_serial (
    commodity_code VARCHAR(6) PRIMARY KEY,
    next_serial BIGINT NOT NULL CHECK (next_serial > 0)
);

INSERT INTO material_code_serial (commodity_code, next_serial)
SELECT COALESCE(mc.code_segment, '40') || COALESCE(mc.code_family, '14') || COALESCE(mc.code_class, '07'),
       COALESCE(MAX(g.code_serial), 0) + 1
FROM material_group g
LEFT JOIN material_category mc ON mc.category_id = g.category_id
GROUP BY COALESCE(mc.code_segment, '40') || COALESCE(mc.code_family, '14') || COALESCE(mc.code_class, '07')
ON CONFLICT (commodity_code) DO UPDATE
SET next_serial = GREATEST(material_code_serial.next_serial, EXCLUDED.next_serial);

CREATE UNIQUE INDEX IF NOT EXISTS uq_group_category_code_serial
    ON material_group(category_id, code_serial) WHERE code_serial IS NOT NULL;

ALTER TABLE material_mapping
    ADD COLUMN IF NOT EXISTS supersedes_mapping_id BIGINT REFERENCES material_mapping(mapping_id),
    ADD COLUMN IF NOT EXISTS superseded_by_mapping_id BIGINT REFERENCES material_mapping(mapping_id);

DROP INDEX IF EXISTS uq_active_mapping_per_material;
CREATE UNIQUE INDEX uq_active_mapping_per_material
    ON material_mapping(material_id)
    WHERE status IN ('PENDING', 'CONFIRMED');
CREATE INDEX IF NOT EXISTS idx_mapping_supersedes ON material_mapping(supersedes_mapping_id);

DELETE FROM group_relation WHERE group_a_id = group_b_id;
DELETE FROM group_relation a
USING group_relation b
WHERE a.relation_id > b.relation_id
  AND LEAST(a.group_a_id, a.group_b_id) = LEAST(b.group_a_id, b.group_b_id)
  AND GREATEST(a.group_a_id, a.group_b_id) = GREATEST(b.group_a_id, b.group_b_id)
  AND a.relation_type = b.relation_type;
UPDATE group_relation
SET group_a_id = LEAST(group_a_id, group_b_id),
    group_b_id = GREATEST(group_a_id, group_b_id);
ALTER TABLE group_relation DROP CONSTRAINT IF EXISTS chk_group_relation_order;
ALTER TABLE group_relation ADD CONSTRAINT chk_group_relation_order CHECK (group_a_id < group_b_id);
