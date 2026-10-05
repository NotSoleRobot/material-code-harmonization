-- Initial reference data. Natural keys are used deliberately so this migration
-- can seed both a new database and a preserved pre-Flyway demonstration database.

INSERT INTO cpse (name, sector)
VALUES
    ('ONGC', 'Oil & Natural Gas Exploration'),
    ('IOCL', 'Refining & Petrochemicals'),
    ('GAIL', 'Natural Gas Transmission'),
    ('BHEL', 'Heavy Electrical Equipment'),
    ('SAIL', 'Steel Manufacturing')
ON CONFLICT (name) DO UPDATE SET sector = EXCLUDED.sector;

-- UNSPSC-aligned segments.
INSERT INTO material_category (name, level, code_segment, description)
SELECT seed.name, 1, seed.segment, seed.description
FROM (VALUES
    ('Distribution and Conditioning Systems (UNSPSC 40)', '40', 'Fluid, gas, and steam piping, valves, and distribution systems'),
    ('Manufacturing and Processing Machinery (UNSPSC 31)', '31', 'Industrial mechanical components, bearings, and hardware'),
    ('Power Generation and Distribution (UNSPSC 26)', '26', 'Electrical machinery, motors, and transmission cables')
) AS seed(name, segment, description)
WHERE NOT EXISTS (SELECT 1 FROM material_category c WHERE c.name = seed.name);

-- UNSPSC-aligned families.
INSERT INTO material_category (name, level, code_segment, code_family, parent_id, description)
SELECT seed.name, 2, seed.segment, seed.family,
       (SELECT MIN(category_id) FROM material_category WHERE name = seed.parent_name),
       seed.description
FROM (VALUES
    ('Fluid and Gas Distribution (40-14)', '40', '14', 'Distribution and Conditioning Systems (UNSPSC 40)', 'Piping, tubing, flanges, and flow control valves'),
    ('Industrial Pumps and Compressors (40-15)', '40', '15', 'Distribution and Conditioning Systems (UNSPSC 40)', 'Fluid displacement equipment and pump spares'),
    ('Bearings and Bushings (31-17)', '31', '17', 'Manufacturing and Processing Machinery (UNSPSC 31)', 'Rotational bearings, roller bearings, and housings'),
    ('Industrial Fasteners and Hardware (31-16)', '31', '16', 'Manufacturing and Processing Machinery (UNSPSC 31)', 'Bolts, nuts, studs, washers, and structural fasteners'),
    ('Electric Motors and Generators (26-10)', '26', '10', 'Power Generation and Distribution (UNSPSC 26)', 'AC/DC motors, drives, and rotary electrical machinery'),
    ('Electrical Wire and Cable (26-12)', '26', '12', 'Power Generation and Distribution (UNSPSC 26)', 'Power, control, and instrumentation cabling')
) AS seed(name, segment, family, parent_name, description)
WHERE NOT EXISTS (SELECT 1 FROM material_category c WHERE c.name = seed.name);

-- Reuse legacy commodity rows so existing material foreign keys remain valid.
INSERT INTO material_category (name, level)
SELECT seed.name, 3
FROM (VALUES ('PIPE'), ('VALVE'), ('FLANGE'), ('PUMP'), ('BEARING'), ('FASTENER'), ('MOTOR'), ('CABLE')) AS seed(name)
WHERE NOT EXISTS (SELECT 1 FROM material_category c WHERE UPPER(c.name) = seed.name);

UPDATE material_category SET level = 3, code_segment = '40', code_family = '14', code_class = '07', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Fluid and Gas Distribution (40-14)'), description = 'Carbon Steel, Stainless Steel, and Alloy Seamless & Welded Pipes' WHERE UPPER(name) = 'PIPE';
UPDATE material_category SET level = 3, code_segment = '40', code_family = '14', code_class = '16', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Fluid and Gas Distribution (40-14)'), description = 'Gate, Globe, Check, Ball, and Butterfly Industrial Valves' WHERE UPPER(name) = 'VALVE';
UPDATE material_category SET level = 3, code_segment = '40', code_family = '14', code_class = '17', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Fluid and Gas Distribution (40-14)'), description = 'Weld Neck, Slip-On, Blind, and Socket Weld Pipe Flanges' WHERE UPPER(name) = 'FLANGE';
UPDATE material_category SET level = 3, code_segment = '40', code_family = '15', code_class = '15', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Industrial Pumps and Compressors (40-15)'), description = 'Centrifugal, Submersible, and Positive Displacement Pumps' WHERE UPPER(name) = 'PUMP';
UPDATE material_category SET level = 3, code_segment = '31', code_family = '17', code_class = '15', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Bearings and Bushings (31-17)'), description = 'Deep Groove Ball, Spherical Roller, and Tapered Bearings' WHERE UPPER(name) = 'BEARING';
UPDATE material_category SET level = 3, code_segment = '31', code_family = '16', code_class = '15', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Industrial Fasteners and Hardware (31-16)'), description = 'High-Tensile Stud Bolts, Hex Nuts, and Industrial Fasteners' WHERE UPPER(name) = 'FASTENER';
UPDATE material_category SET level = 3, code_segment = '26', code_family = '10', code_class = '11', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Electric Motors and Generators (26-10)'), description = 'Low Voltage and High Voltage 3-Phase Induction Motors' WHERE UPPER(name) = 'MOTOR';
UPDATE material_category SET level = 3, code_segment = '26', code_family = '12', code_class = '16', parent_id = (SELECT MIN(category_id) FROM material_category WHERE name = 'Electrical Wire and Cable (26-12)'), description = 'Armoured Power, Control, and XLPE Insulated Instrumentation Cables' WHERE UPPER(name) = 'CABLE';

-- Passwords: admin123, reviewer123, and operator123 respectively.
INSERT INTO "user" (name, email, password_hash, role, cpse_id, active)
VALUES
    ('System Administrator (MoPNG & CMCC)', 'admin@numm.gov.in', crypt('admin123', gen_salt('bf', 12)), 'ADMIN', NULL, true),
    ('Dr. Rajesh Sharma (Senior Catalog Approver)', 'senior.reviewer@numm.gov.in', crypt('reviewer123', gen_salt('bf', 12)), 'SENIOR_REVIEWER', NULL, true),
    ('Dr. S. Ananth (Mechanical Review Specialist)', 'lead.reviewer@numm.gov.in', crypt('reviewer123', gen_salt('bf', 12)), 'REVIEWER', NULL, true),
    ('Priya Nambiar (Electrical & Systems Reviewer)', 'reviewer.electrical@numm.gov.in', crypt('reviewer123', gen_salt('bf', 12)), 'REVIEWER', NULL, true),
    ('Ramesh Nair (CPSE Operator - ONGC)', 'operator@ongc.co.in', crypt('operator123', gen_salt('bf', 12)), 'OPERATOR', (SELECT cpse_id FROM cpse WHERE name = 'ONGC'), true),
    ('Sunita Verma (CPSE Operator - IOCL)', 'operator@iocl.in', crypt('operator123', gen_salt('bf', 12)), 'OPERATOR', (SELECT cpse_id FROM cpse WHERE name = 'IOCL'), true),
    ('Amit Patel (CPSE Operator - GAIL)', 'operator@gail.co.in', crypt('operator123', gen_salt('bf', 12)), 'OPERATOR', (SELECT cpse_id FROM cpse WHERE name = 'GAIL'), true),
    ('Rajesh Kumar (CPSE Operator - BHEL)', 'operator@bhel.in', crypt('operator123', gen_salt('bf', 12)), 'OPERATOR', (SELECT cpse_id FROM cpse WHERE name = 'BHEL'), true),
    ('Vikram Singh (CPSE Operator - SAIL)', 'operator@sail.in', crypt('operator123', gen_salt('bf', 12)), 'OPERATOR', (SELECT cpse_id FROM cpse WHERE name = 'SAIL'), true)
ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name, password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, cpse_id = EXCLUDED.cpse_id, active = EXCLUDED.active;

INSERT INTO reviewer_assignment (user_id, category_id)
SELECT u.user_id, c.category_id FROM "user" u CROSS JOIN material_category c
WHERE u.email = 'lead.reviewer@numm.gov.in' AND UPPER(c.name) IN ('PIPE', 'VALVE', 'FLANGE', 'PUMP', 'BEARING', 'FASTENER')
ON CONFLICT (user_id, category_id) DO NOTHING;

INSERT INTO reviewer_assignment (user_id, category_id)
SELECT u.user_id, c.category_id FROM "user" u CROSS JOIN material_category c
WHERE u.email = 'reviewer.electrical@numm.gov.in' AND UPPER(c.name) IN ('MOTOR', 'CABLE')
ON CONFLICT (user_id, category_id) DO NOTHING;

INSERT INTO procurement_assumption (key, name, description, value, unit, updated_by)
VALUES
    ('carrying_cost_annual_inr', 'Annual Inventory Carrying Cost per Duplicate SKU', 'Industry standard 18-22% holding cost on working capital and warehousing for redundant catalog items', 45000.00, 'INR / SKU / Year', (SELECT user_id FROM "user" WHERE email = 'admin@numm.gov.in')),
    ('master_data_cleanup_avoided_inr', 'Master Data Cleansing Avoidance', 'One-off cost avoided per SKU on third-party manual catalog sanitization and ERP migration consulting', 25000.00, 'INR / SKU one-off', (SELECT user_id FROM "user" WHERE email = 'admin@numm.gov.in')),
    ('price_variance_recovery_pct', 'Price Variance Recovery through Rate Contracts', 'Average estimated procurement price reduction through joint GeM / Central PSU rate contracts on unified SKUs', 3.50, '% of procurement spend', (SELECT user_id FROM "user" WHERE email = 'admin@numm.gov.in')),
    ('admin_overhead_reduction_inr', 'Procurement Tender Admin Overhead Reduction', 'Savings in tender preparation, vendor technical evaluation, and RFQ administration per unified commodity', 15000.00, 'INR / Tender', (SELECT user_id FROM "user" WHERE email = 'admin@numm.gov.in'))
ON CONFLICT (key) DO NOTHING;

SELECT setval('cpse_cpse_id_seq', COALESCE((SELECT MAX(cpse_id) FROM cpse), 1), true);
SELECT setval('material_category_category_id_seq', COALESCE((SELECT MAX(category_id) FROM material_category), 1), true);
SELECT setval('user_user_id_seq', COALESCE((SELECT MAX(user_id) FROM "user"), 1), true);
