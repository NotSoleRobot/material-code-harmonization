-- Runs after Hibernate creates/updates tables from the entities
-- (see spring.jpa.defer-datasource-initialization=true in application.properties).
-- Adds the two rules JPA annotations cannot express — flagged in the
-- User.java and MaterialMapping.java entity comments, locked in
-- Material_Master_Project_CONTEXT_v3.md Section 7.

-- Rule: an OPERATOR must have a cpse_id; a REVIEWER/ADMIN must not.
-- DROP...IF EXISTS first so this file is safe to re-run on every app restart.
ALTER TABLE "user"
    DROP CONSTRAINT IF EXISTS chk_operator_has_cpse;

ALTER TABLE "user"
    ADD CONSTRAINT chk_operator_has_cpse CHECK (
        (role = 'OPERATOR' AND cpse_id IS NOT NULL)
        OR (role IN ('REVIEWER', 'ADMIN') AND cpse_id IS NULL)
    );

-- Rule: a material can have unlimited REJECTED mapping history,
-- but only one active (PENDING/CONFIRMED) mapping at a time.
DROP INDEX IF EXISTS uq_active_mapping_per_material;

CREATE UNIQUE INDEX uq_active_mapping_per_material
    ON material_mapping (material_id)
    WHERE status != 'REJECTED';
