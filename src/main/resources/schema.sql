-- Systems-of-record schema (plan.md §3.1, §3.5). The RAG knowledge-corpus
-- table (rag_documents) is managed separately by PgVectorEmbeddingStore
-- (com.aigre.config.RagConfig), not defined here. Likewise, the LangGraph4j
-- checkpoint tables (lg4jthread, lg4jcheckpoint) are created and owned by
-- PostgresSaver (com.aigre.workflow.GrievanceWorkflowGraphConfig), not defined
-- here -- that's what lets a paused human-review workflow survive an app restart.

CREATE EXTENSION IF NOT EXISTS vector;

-- Backs GrievanceIdGenerator's app-side "G0001"-style ID minting -- see the
-- idempotent ALTER block after status_history below for the one-time column
-- retype from UUID that made this necessary.
CREATE SEQUENCE IF NOT EXISTS grievance_id_seq START 1;

CREATE TABLE IF NOT EXISTS departments (
    id VARCHAR(10) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    jurisdiction_notes TEXT
);

-- short_name is the parenthetical label used in the classifier prompt's DEPARTMENTS bullets
-- (e.g. "DOT (Transportation)") -- not derivable from `name` by stripping "Department of " for
-- all rows (DHHS/DHUD use "&", not "and"), so it's its own column rather than munged at read time.
ALTER TABLE departments ADD COLUMN IF NOT EXISTS short_name VARCHAR(60);

CREATE TABLE IF NOT EXISTS department_employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id VARCHAR(10) REFERENCES departments (id),
    name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('AGENT', 'SUPERVISOR')),
    username VARCHAR(60) UNIQUE,
    password_hash VARCHAR(100)
);

-- No migration tool in this project (schema.sql's CREATE TABLE IF NOT EXISTS is a no-op against
-- an already-existing table) -- these ALTERs let username/password_hash reach an existing
-- aigre-pg database from an earlier session, not just a fresh one.
ALTER TABLE department_employees ADD COLUMN IF NOT EXISTS username VARCHAR(60) UNIQUE;
ALTER TABLE department_employees ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);

-- ADMIN: a cross-department oversight role, added after AGENT/SUPERVISOR. department_id is
-- already nullable (see the column above) -- an ADMIN's row simply has no department_id, which
-- com.aigre.auth.DepartmentAccess and GrievanceQueryService.list() both already treat as
-- "no department filter" once the CHECK constraint allows the value through.
ALTER TABLE department_employees DROP CONSTRAINT IF EXISTS department_employees_role_check;
ALTER TABLE department_employees ADD CONSTRAINT department_employees_role_check
    CHECK (role IN ('AGENT', 'SUPERVISOR', 'ADMIN'));

CREATE TABLE IF NOT EXISTS citizens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120),
    email VARCHAR(160),
    phone VARCHAR(30),
    preferred_contact VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sla_policies (
    priority VARCHAR(10) PRIMARY KEY CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    ack_hours INT NOT NULL,
    resolve_hours INT NOT NULL
);

-- department_predicted / department_confirmed / assigned_department are
-- deliberately NOT foreign keys: plan.md §3.5 requires seeding a legacy row
-- with a department code that no longer exists, to exercise MCP tool error
-- paths in milestone 3. A hard FK would make that edge case unseedable.
CREATE TABLE IF NOT EXISTS grievances (
    id VARCHAR PRIMARY KEY,
    channel VARCHAR(10) NOT NULL DEFAULT 'PORTAL' CHECK (channel IN ('PORTAL', 'EMAIL')),
    citizen_id UUID REFERENCES citizens (id),
    raw_text TEXT NOT NULL,
    department_predicted VARCHAR(10),
    department_confirmed VARCHAR(10),
    category VARCHAR(80),
    subcategory VARCHAR(80),
    priority VARCHAR(10) CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    classification_confidence DOUBLE PRECISION,
    sentiment_label VARCHAR(10) CHECK (sentiment_label IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE')),
    sentiment_score DOUBLE PRECISION,
    status VARCHAR(24) NOT NULL DEFAULT 'NEW',
    sla_due_at TIMESTAMPTZ,
    assigned_department VARCHAR(10),
    assigned_employee_id UUID REFERENCES department_employees (id),
    duplicate_of_id VARCHAR REFERENCES grievances (id),
    resolution_notes TEXT,
    resolved_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per citizen clarify() call (com.aigre.workflow.GrievanceWorkflowService.clarify).
-- grievances.raw_text is never mutated after the original submission -- this table is the sole
-- source of follow-up detail, so the employee dashboard can render the original complaint and
-- each follow-up as distinct entries instead of one concatenated blob.
CREATE TABLE IF NOT EXISTS grievance_clarifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grievance_id VARCHAR NOT NULL REFERENCES grievances (id),
    additional_text TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grievance_id VARCHAR NOT NULL REFERENCES grievances (id),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    changed_by VARCHAR(120),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note TEXT
);

-- One-time migration for an already-seeded database (this dev DB included), where the
-- CREATE TABLE IF NOT EXISTS statements above are no-ops against existing tables. Safe to
-- leave here permanently and re-run on every boot: once the columns are already VARCHAR,
-- dropping/re-adding the FK constraints and retyping is a no-op in effect (Postgres has no
-- ADD CONSTRAINT IF NOT EXISTS, so this is unconditional drop-then-add rather than a guard).
ALTER TABLE grievances DROP CONSTRAINT IF EXISTS grievances_duplicate_of_id_fkey;
ALTER TABLE grievance_clarifications DROP CONSTRAINT IF EXISTS grievance_clarifications_grievance_id_fkey;
ALTER TABLE status_history DROP CONSTRAINT IF EXISTS status_history_grievance_id_fkey;

ALTER TABLE grievances ALTER COLUMN id DROP DEFAULT;
ALTER TABLE grievances ALTER COLUMN id TYPE VARCHAR;
ALTER TABLE grievances ALTER COLUMN duplicate_of_id TYPE VARCHAR;
ALTER TABLE grievance_clarifications ALTER COLUMN grievance_id TYPE VARCHAR;
ALTER TABLE status_history ALTER COLUMN grievance_id TYPE VARCHAR;

ALTER TABLE grievances ADD CONSTRAINT grievances_duplicate_of_id_fkey
    FOREIGN KEY (duplicate_of_id) REFERENCES grievances (id);
ALTER TABLE grievance_clarifications ADD CONSTRAINT grievance_clarifications_grievance_id_fkey
    FOREIGN KEY (grievance_id) REFERENCES grievances (id);
ALTER TABLE status_history ADD CONSTRAINT status_history_grievance_id_fkey
    FOREIGN KEY (grievance_id) REFERENCES grievances (id);

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

INSERT INTO sla_policies (priority, ack_hours, resolve_hours) VALUES
    ('CRITICAL', 1, 4),
    ('HIGH', 4, 24),
    ('MEDIUM', 24, 120),
    ('LOW', 48, 360)
ON CONFLICT (priority) DO NOTHING;
