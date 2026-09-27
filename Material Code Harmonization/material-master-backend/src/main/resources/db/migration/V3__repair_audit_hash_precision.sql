-- Rebuild the chain from stored timestamps. Older application versions hashed
-- nanoseconds before PostgreSQL truncated them to microseconds, which made valid
-- rows fail verification after they were read back.
DO $$
DECLARE
    audit_row RECORD;
    previous_hash TEXT := repeat('0', 64);
    current_hash TEXT;
    timestamp_text TEXT;
    micros TEXT;
BEGIN
    FOR audit_row IN SELECT * FROM audit_trail ORDER BY audit_id LOOP
        micros := to_char(audit_row.timestamp, 'US');
        timestamp_text := to_char(audit_row.timestamp, 'YYYY-MM-DD"T"HH24:MI:SS') ||
            CASE
                WHEN micros = '000000' THEN ''
                WHEN right(micros, 3) = '000' THEN '.' || left(micros, 3)
                ELSE '.' || micros
            END;

        current_hash := UPPER(encode(digest(
            convert_to(
                previous_hash || '|' || COALESCE(audit_row.user_id, 0)::text || '|' ||
                audit_row.action || '|' || audit_row.entity_type || '|' ||
                audit_row.entity_id::text || '|' || timestamp_text || '|' ||
                COALESCE(audit_row.new_value, ''),
                'UTF8'
            ),
            'sha256'
        ), 'hex'));

        UPDATE audit_trail
        SET prev_hash = previous_hash, row_hash = current_hash
        WHERE audit_id = audit_row.audit_id;

        previous_hash := current_hash;
    END LOOP;
END $$;
