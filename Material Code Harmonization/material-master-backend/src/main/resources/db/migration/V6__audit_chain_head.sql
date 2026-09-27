-- V6__audit_chain_head.sql
-- WP5 / D5: Hardens the cryptographic audit chain.
-- 1. Anchor table with row-locking for serial append
-- 2. Database-level append-only trigger enforcing immutability

CREATE TABLE IF NOT EXISTS audit_chain_head (
    id        INTEGER PRIMARY KEY DEFAULT 1,
    head_hash VARCHAR(64) NOT NULL,
    CONSTRAINT one_row CHECK (id = 1)
);

INSERT INTO audit_chain_head (id, head_hash)
SELECT 1, COALESCE(
    (SELECT row_hash FROM audit_trail ORDER BY audit_id DESC LIMIT 1),
    repeat('0', 64)
)
ON CONFLICT (id) DO NOTHING;

-- Database-level append-only guard: forbids UPDATE and DELETE on audit_trail
CREATE OR REPLACE FUNCTION audit_trail_is_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_trail is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_no_update ON audit_trail;

CREATE TRIGGER trg_audit_no_update
    BEFORE UPDATE OR DELETE ON audit_trail
    FOR EACH ROW EXECUTE FUNCTION audit_trail_is_append_only();
