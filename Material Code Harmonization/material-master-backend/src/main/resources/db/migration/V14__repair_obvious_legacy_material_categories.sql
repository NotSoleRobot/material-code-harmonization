-- Repair only unambiguous legacy category errors. Earlier demonstration data
-- could leave material.category_id empty while attaching its proposed group to
-- PIPE. The rules below use explicit commodity words, never fuzzy inference.
WITH inferred_material_category AS (
    SELECT m.material_id,
           CASE
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(BEARING|BUSHING)([^A-Z]|$)' THEN 'BEARING'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(VALVE)([^A-Z]|$)' THEN 'VALVE'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(FLANGE)([^A-Z]|$)' THEN 'FLANGE'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(PUMP)([^A-Z]|$)' THEN 'PUMP'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(MOTOR)([^A-Z]|$)' THEN 'MOTOR'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(CABLE)([^A-Z]|$)' THEN 'CABLE'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(BOLT|NUT|FASTENER|WASHER|STUD)([^A-Z]|$)' THEN 'FASTENER'
               WHEN UPPER(m.description) ~ '(^|[^A-Z])(PIPE|TUBE|TUBING)([^A-Z]|$)' THEN 'PIPE'
               ELSE NULL
           END AS category_name
    FROM material m
), resolved AS (
    SELECT i.material_id, c.category_id
    FROM inferred_material_category i
    JOIN material_category c ON UPPER(c.name) = i.category_name
    WHERE i.category_name IS NOT NULL AND c.level = 3
)
UPDATE material m
SET category_id = r.category_id
FROM resolved r
WHERE m.material_id = r.material_id
  AND m.category_id IS DISTINCT FROM r.category_id;

-- A proposed group is corrected only when every attached material now agrees
-- on one category. Mixed groups are intentionally left untouched for review.
WITH group_consensus AS (
    SELECT mm.group_id, MIN(m.category_id) AS category_id
    FROM material_mapping mm
    JOIN material m ON m.material_id = mm.material_id
    WHERE m.category_id IS NOT NULL
    GROUP BY mm.group_id
    HAVING COUNT(DISTINCT m.category_id) = 1
)
UPDATE material_group g
SET category_id = consensus.category_id
FROM group_consensus consensus
WHERE g.group_id = consensus.group_id
  AND g.status = 'PROPOSED'
  AND g.category_id IS DISTINCT FROM consensus.category_id;
