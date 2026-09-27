-- V3 repaired timestamp precision for historical rows during the original
-- migration sequence. It is historical and must never be copied, rerun, or
-- used as an operational chain-repair mechanism. All subsequent audit rows
-- are append-only and use the serialized audit_chain_head writer.
SELECT 1;
