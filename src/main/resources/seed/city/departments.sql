-- City departments for the default (city) domain profile. Runs after schema.sql on every boot
-- (spring.sql.init.data-locations); the dmv profile points data-locations at seed/dmv/ instead.
-- Moved verbatim out of schema.sql so a non-city deployment doesn't get these six rows back on
-- every restart.

-- jurisdiction_notes below is the classifier's actual DEPARTMENTS prompt-bullet text, verbatim
-- (including DPW's cross-department disambiguation sentences, which fixed a real documented
-- classification bug -- hazard-adjacent DPW infrastructure incidents being misrouted to DEP) --
-- not a thinner summary. DepartmentDirectory builds the live prompt section from this column, so
-- a new database boots with classifier-quality text already in place, not a placeholder.
INSERT INTO departments (id, name, short_name, jurisdiction_notes) VALUES
    ('DOT', 'Department of Transportation', 'Transportation',
     'road surface/potholes, traffic signals, public transit, street signage, bike lanes, school-zone traffic safety, railroad crossings, vehicle-for-hire licensing.'),
    ('DPW', 'Department of Public Works', 'Public Works',
     'sidewalks, water/sewer mains, street lighting, trash/recycling collection, snow removal, right-of-way trees, graffiti on public property, storm drains, DPW-managed public buildings (city hall, libraries). DPW owns infrastructure hazards even when they sound environmental -- a gas smell from a sewer/manhole, a downed pole or exposed wiring -- because DPW is the one who fixes the underlying infrastructure. DEP''s hazardous-waste category is for abandoned or dumped chemical containers, NOT for incidents involving DPW-owned infrastructure.'),
    ('DHHS', 'Department of Health and Human Services', 'Health & Human Services',
     'food safety at licensed establishments, benefits eligibility/appeals, elder/adult protective services, mandatory reporting (child welfare), senior nutrition, public health nuisances on private property, immunization access, mental health crisis, homeless health outreach, child care licensing.'),
    ('DOE', 'Department of Education', 'Education',
     'school facilities, student safety/bullying (peer-to-peer), teacher conduct (staff-to-student), special education services, school health services, school transportation, enrollment, free/reduced lunch, after-school programs, ADA accessibility at school buildings, truancy.'),
    ('DHUD', 'Department of Housing and Urban Development', 'Housing & Urban Development',
     'subsidized-housing habitability, in-unit utility outages, public housing maintenance, homelessness shelter referral, fair housing discrimination, housing vouchers, first-time homebuyer assistance, lead paint, eviction prevention.'),
    ('DEP', 'Department of Environmental Protection', 'Environmental Protection',
     'illegal dumping, noise ordinance, air quality, drinking water quality/contamination, recycling/composting program design, pesticide/herbicide complaints, wetlands/stormwater contamination, hazardous waste, protected/heritage trees, vehicle emissions, construction dust.')
ON CONFLICT (id) DO NOTHING;

-- One-time backfill for an already-seeded database (this dev DB included), where the INSERT
-- above is a no-op against existing rows. Guarded by short_name IS NULL so it never re-runs once
-- applied, even though schema.sql executes on every boot (spring.sql.init.mode: always).
UPDATE departments SET short_name = 'Transportation',
    jurisdiction_notes = 'road surface/potholes, traffic signals, public transit, street signage, bike lanes, school-zone traffic safety, railroad crossings, vehicle-for-hire licensing.'
    WHERE id = 'DOT' AND short_name IS NULL;
UPDATE departments SET short_name = 'Public Works',
    jurisdiction_notes = 'sidewalks, water/sewer mains, street lighting, trash/recycling collection, snow removal, right-of-way trees, graffiti on public property, storm drains, DPW-managed public buildings (city hall, libraries). DPW owns infrastructure hazards even when they sound environmental -- a gas smell from a sewer/manhole, a downed pole or exposed wiring -- because DPW is the one who fixes the underlying infrastructure. DEP''s hazardous-waste category is for abandoned or dumped chemical containers, NOT for incidents involving DPW-owned infrastructure.'
    WHERE id = 'DPW' AND short_name IS NULL;
UPDATE departments SET short_name = 'Health & Human Services',
    jurisdiction_notes = 'food safety at licensed establishments, benefits eligibility/appeals, elder/adult protective services, mandatory reporting (child welfare), senior nutrition, public health nuisances on private property, immunization access, mental health crisis, homeless health outreach, child care licensing.'
    WHERE id = 'DHHS' AND short_name IS NULL;
UPDATE departments SET short_name = 'Education',
    jurisdiction_notes = 'school facilities, student safety/bullying (peer-to-peer), teacher conduct (staff-to-student), special education services, school health services, school transportation, enrollment, free/reduced lunch, after-school programs, ADA accessibility at school buildings, truancy.'
    WHERE id = 'DOE' AND short_name IS NULL;
UPDATE departments SET short_name = 'Housing & Urban Development',
    jurisdiction_notes = 'subsidized-housing habitability, in-unit utility outages, public housing maintenance, homelessness shelter referral, fair housing discrimination, housing vouchers, first-time homebuyer assistance, lead paint, eviction prevention.'
    WHERE id = 'DHUD' AND short_name IS NULL;
UPDATE departments SET short_name = 'Environmental Protection',
    jurisdiction_notes = 'illegal dumping, noise ordinance, air quality, drinking water quality/contamination, recycling/composting program design, pesticide/herbicide complaints, wetlands/stormwater contamination, hazardous waste, protected/heritage trees, vehicle emissions, construction dust.'
    WHERE id = 'DEP' AND short_name IS NULL;
