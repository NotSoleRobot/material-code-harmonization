-- One-time final demonstration reset. Reference categories, policies, CPSEs and
-- the three role accounts are preserved; transactional prototype data is removed.
TRUNCATE TABLE material, material_group, harmonization_job, audit_trail, material_code_serial
    RESTART IDENTITY CASCADE;

ALTER SEQUENCE IF EXISTS numm_serial_seq RESTART WITH 1;

INSERT INTO audit_chain_head (id, head_hash)
VALUES (1, repeat('0', 64))
ON CONFLICT (id) DO UPDATE SET head_hash = EXCLUDED.head_hash;
