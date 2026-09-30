-- Historical mappings predate score-driven routing. Do not misrepresent them as
-- automatic decisions merely because their current status is CONFIRMED.
UPDATE material_mapping
SET routing_decision = 'REVIEW_REQUIRED'
WHERE model_version IS NULL
  AND decision_source IN ('HUMAN', 'LEGACY');

ALTER TABLE material_mapping DROP CONSTRAINT IF EXISTS chk_mapping_decision_source;
ALTER TABLE material_mapping ADD CONSTRAINT chk_mapping_decision_source
    CHECK (decision_source IN ('AUTO', 'HUMAN', 'LEGACY'));

ALTER TABLE material_mapping DROP CONSTRAINT IF EXISTS chk_mapping_routing_decision;
ALTER TABLE material_mapping ADD CONSTRAINT chk_mapping_routing_decision
    CHECK (routing_decision IN ('AUTO_CONFIRM', 'REVIEW_REQUIRED', 'NOVEL'));
