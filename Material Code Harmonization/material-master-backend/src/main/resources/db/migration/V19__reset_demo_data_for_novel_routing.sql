-- Reset only transactional prototype data after enabling direct registration of
-- complete novel materials. Reference categories, CPSEs, matching policies and
-- the three demo login accounts are intentionally preserved.
TRUNCATE TABLE material, material_group, harmonization_job, audit_trail, material_code_serial
    RESTART IDENTITY CASCADE;

ALTER SEQUENCE IF EXISTS numm_serial_seq RESTART WITH 1;

INSERT INTO audit_chain_head (id, head_hash)
VALUES (1, repeat('0', 64))
ON CONFLICT (id) DO UPDATE SET head_hash = EXCLUDED.head_hash;
