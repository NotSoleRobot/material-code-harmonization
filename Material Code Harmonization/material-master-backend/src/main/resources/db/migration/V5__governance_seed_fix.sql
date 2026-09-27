-- V5__governance_seed_fix.sql
-- WP4 Task 1: Update seeded user accounts with proper CPSE affiliations and @numm.gov.in emails
-- Enables demonstrable 4-eyes review and Conflict of Interest (COI) enforcement.

UPDATE "user"
SET email = 'admin@numm.gov.in'
WHERE email LIKE '%admin%@numm.gov.in' OR email = 'admin@numm.gov.in';

UPDATE "user"
SET email = 'senior.reviewer@numm.gov.in', cpse_id = NULL
WHERE email LIKE '%senior.reviewer%@numm.gov.in' OR email = 'senior.reviewer@numm.gov.in';

UPDATE "user"
SET email = 'reviewer.mech@numm.gov.in',
    name = 'Dr. S. Ananth (Mechanical Reviewer - IOCL)',
    cpse_id = (SELECT cpse_id FROM cpse WHERE name = 'IOCL')
WHERE email LIKE '%lead.reviewer%' OR email LIKE '%reviewer.mech%';

UPDATE "user"
SET email = 'reviewer.elec@numm.gov.in',
    name = 'Priya Nambiar (Electrical Reviewer - GAIL)',
    cpse_id = (SELECT cpse_id FROM cpse WHERE name = 'GAIL')
WHERE email LIKE '%reviewer.electrical%' OR email LIKE '%reviewer.elec%';
