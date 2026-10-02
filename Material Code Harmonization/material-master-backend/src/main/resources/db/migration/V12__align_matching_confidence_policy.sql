-- Align persisted matching policies with the approved confidence routing rules.
-- Existing category-specific values are retained unless they still contain the
-- former system defaults.
ALTER TABLE matching_policy
    ALTER COLUMN auto_confirm_threshold SET DEFAULT 0.8500,
    ALTER COLUMN review_threshold SET DEFAULT 0.6000;

UPDATE matching_policy
SET auto_confirm_threshold = 0.8500,
    policy_version = '1.1'
WHERE auto_confirm_threshold = 0.9000;

UPDATE matching_policy
SET review_threshold = 0.6000,
    policy_version = '1.1'
WHERE review_threshold = 0.7000;
