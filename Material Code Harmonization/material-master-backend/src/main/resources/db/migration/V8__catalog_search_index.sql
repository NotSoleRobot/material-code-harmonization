CREATE INDEX IF NOT EXISTS idx_group_desc_lower
    ON material_group (LOWER(standardized_description));
