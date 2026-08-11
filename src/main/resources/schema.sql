-- Systems-of-record schema (plan.md §3.1, §3.5). The RAG knowledge-corpus
-- table (rag_documents) is managed separately by PgVectorEmbeddingStore
-- (com.aigre.config.RagConfig), not defined here.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS departments (
    id VARCHAR(10) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    jurisdiction_notes TEXT
);

CREATE TABLE IF NOT EXISTS department_employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id VARCHAR(10) REFERENCES departments (id),
    name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('AGENT', 'SUPERVISOR'))
);

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
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    duplicate_of_id UUID REFERENCES grievances (id),
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
    grievance_id UUID NOT NULL REFERENCES grievances (id),
    additional_text TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grievance_id UUID NOT NULL REFERENCES grievances (id),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    changed_by VARCHAR(120),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note TEXT
);

INSERT INTO departments (id, name, jurisdiction_notes) VALUES
    ('DOT', 'Department of Transportation', 'Roads, traffic signals, public transit, signage'),
    ('DPW', 'Department of Public Works', 'Sidewalks, water/sewer mains, street lighting, trash collection, snow removal'),
    ('DHHS', 'Department of Health and Human Services', 'Food safety, elder/social services, benefits eligibility, mandatory-reporting cases'),
    ('DOE', 'Department of Education', 'School facilities, student safety, special education, bullying'),
    ('DHUD', 'Department of Housing and Urban Development', 'Landlord-tenant, code violations, public housing maintenance, homelessness services'),
    ('DEP', 'Department of Environmental Protection', 'Illegal dumping, noise ordinance, air quality, pollution')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sla_policies (priority, ack_hours, resolve_hours) VALUES
    ('CRITICAL', 1, 4),
    ('HIGH', 4, 24),
    ('MEDIUM', 24, 120),
    ('LOW', 48, 360)
ON CONFLICT (priority) DO NOTHING;
