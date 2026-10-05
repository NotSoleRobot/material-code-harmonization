-- Submission reset: remove stale prototype/demo transactions while preserving
-- reference categories, CPSEs, matching policies, and the three demo accounts.
TRUNCATE TABLE material, material_group, harmonization_job, audit_trail, material_code_serial
    RESTART IDENTITY CASCADE;

ALTER SEQUENCE IF EXISTS numm_serial_seq RESTART WITH 1;

INSERT INTO audit_chain_head (id, head_hash)
VALUES (1, repeat('0', 64))
ON CONFLICT (id) DO UPDATE SET head_hash = EXCLUDED.head_hash;

UPDATE "user" SET name = CASE role
    WHEN 'ADMIN' THEN 'Central Administrator'
    WHEN 'SENIOR_REVIEWER' THEN 'Senior Reviewer'
    WHEN 'OPERATOR' THEN 'CPSE Operator'
    ELSE role
END
WHERE role IN ('ADMIN', 'SENIOR_REVIEWER', 'OPERATOR');
