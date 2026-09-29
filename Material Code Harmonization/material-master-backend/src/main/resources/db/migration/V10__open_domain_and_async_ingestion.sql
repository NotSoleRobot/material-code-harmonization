ALTER TABLE material_category
    ADD COLUMN IF NOT EXISTS is_custom BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS uq_material_category_name_lower
    ON material_category (lower(name));

INSERT INTO material_category
    (name, level, code_segment, code_family, code_class, description, is_custom)
VALUES
    ('GENERAL_MRO', 3, '99', '99', '99',
     'Open-domain industrial MRO materials outside configured specialist taxonomies', true)
ON CONFLICT DO NOTHING;

ALTER TABLE harmonization_job
    ADD COLUMN IF NOT EXISTS imported_items INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS skipped_items INT NOT NULL DEFAULT 0;

INSERT INTO procurement_assumption (key, name, description, value, unit)
VALUES ('inventory_carrying_cost_rate_pct', 'Annual Inventory Carrying Cost Rate',
        'Percentage of redundant inventory nominal value avoided annually', 20.00, 'Percent / Year')
ON CONFLICT (key) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_group_description_trgm
    ON material_group USING gin (lower(standardized_description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_group_common_code_lower
    ON material_group (lower(common_material_code));
CREATE INDEX IF NOT EXISTS idx_group_provisional_ref_lower
    ON material_group (lower(provisional_ref));
