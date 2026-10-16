-- AIGRE demo seed for the dmv domain profile (application-dmv.yml). Run against the DMV database
-- AFTER the app has booted once against it -- schema.sql creates the tables and
-- seed/dmv/departments.sql inserts the five bureaus. Operational/demo rows only: the city seed's
-- deliberate MCP edge cases (test-data/sql/seed.sql) aren't repeated here.
--
-- Same ID conventions as the city seed: employees 'ee', citizens 'c0', status history 'b0'.

--------------------------------------------------------------------------
-- Bureau employees (1 agent + 1 supervisor per bureau) and the ADMIN
--------------------------------------------------------------------------

-- Every account shares the demo password "Demo1234!" (same bcrypt hash as the city seed). This is
-- public, committed data: replace the password before exposing any deployment beyond your own
-- machine or LAN (docs/RUNNING.md).
INSERT INTO department_employees (id, department_id, name, role, username, password_hash) VALUES
    ('ee000000-0000-0000-0000-000000000101', 'REG', 'Erin Callahan', 'AGENT', 'erin.callahan', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000102', 'REG', 'Victor Osei', 'SUPERVISOR', 'victor.osei', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000103', 'DL', 'Hannah Pruitt', 'AGENT', 'hannah.pruitt', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000104', 'DL', 'Luis Ferreira', 'SUPERVISOR', 'luis.ferreira', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000105', 'FR', 'Maya Lindqvist', 'AGENT', 'maya.lindqvist', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000106', 'FR', 'Owen Brightwater', 'SUPERVISOR', 'owen.brightwater', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000107', 'TITLE', 'Priscilla Moreau', 'AGENT', 'priscilla.moreau', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000108', 'TITLE', 'Dev Raman', 'SUPERVISOR', 'dev.raman', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000109', 'DEALER', 'Colleen Shaw', 'AGENT', 'colleen.shaw', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000110', 'DEALER', 'Tomas Novak', 'SUPERVISOR', 'tomas.novak', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000113', NULL, 'Ops Admin', 'ADMIN', 'ops.admin', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm')
ON CONFLICT (id) DO NOTHING;

INSERT INTO citizens (id, name, email, phone, preferred_contact) VALUES
    ('c0000000-0000-0000-0000-000000000101', 'Janet Kowalski', 'janet.kowalski@example.com', '555-020-1180', 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000102', 'Samuel Okafor', 'samuel.okafor@example.com', NULL, 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000103', 'Rita Gallagher', NULL, '555-020-4471', 'PHONE'),
    ('c0000000-0000-0000-0000-000000000104', 'Ben Tran', 'ben.tran@example.com', '555-020-9036', 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000105', 'Alicia Romero', 'alicia.romero@example.com', NULL, 'EMAIL')
ON CONFLICT (id) DO NOTHING;

--------------------------------------------------------------------------
-- Demo grievances, mixed bureaus and statuses
--------------------------------------------------------------------------

INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, subcategory, priority, classification_confidence, sentiment_label, sentiment_score,
    status, sla_due_at, assigned_department, assigned_employee_id, resolution_notes, resolved_at, submitted_at
) VALUES
    ('G0001', 'PORTAL', 'c0000000-0000-0000-0000-000000000101',
     'I mailed in my registration renewal with a check three weeks ago and still have no sticker. My registration expires at the end of the month.',
     'REG', 'REG', 'registration-renewal', NULL, 'MEDIUM', 0.9, 'NEGATIVE', -0.4,
     'IN_PROGRESS', now() + interval '2 days', 'REG', 'ee000000-0000-0000-0000-000000000101',
     NULL, NULL, now() - interval '3 days'),

    ('G0002', 'PORTAL', 'c0000000-0000-0000-0000-000000000102',
     'I lost my title when I moved and now I am trying to sell the car. How do I get a duplicate?',
     'TITLE', 'TITLE', 'duplicate-title', NULL, 'MEDIUM', 0.93, 'NEUTRAL', -0.1,
     'RESOLVED', now() - interval '1 day', 'TITLE', 'ee000000-0000-0000-0000-000000000107',
     'Duplicate title application (TDMV 18) processed and mailed.', now() - interval '2 days', now() - interval '8 days'),

    ('G0003', 'EMAIL', 'c0000000-0000-0000-0000-000000000103',
     'My license shows as suspended for no insurance but I have had continuous coverage with the same company for six years. I drive for a living.',
     'FR', 'FR', 'insurance-suspension', NULL, 'HIGH', 0.88, 'NEGATIVE', -0.7,
     'ROUTED', now() + interval '20 hours', 'FR', 'ee000000-0000-0000-0000-000000000105',
     NULL, NULL, now() - interval '4 hours'),

    ('G0004', 'PORTAL', 'c0000000-0000-0000-0000-000000000104',
     'The inspection station failed my car for a check engine light that is not on. I think they want me to pay for repairs I do not need.',
     'DEALER', 'DEALER', 'inspection-dispute', NULL, 'MEDIUM', 0.84, 'NEGATIVE', -0.6,
     'TRIAGED', now() + interval '4 days', 'DEALER', NULL,
     NULL, NULL, now() - interval '1 day'),

    ('G0005', 'PORTAL', 'c0000000-0000-0000-0000-000000000105',
     'I scheduled my road test online but when I arrived the office said there was no appointment on file.',
     'DL', 'DL', 'road-test', NULL, 'MEDIUM', 0.91, 'NEGATIVE', -0.5,
     'RESOLVED', now() - interval '3 days', 'DL', 'ee000000-0000-0000-0000-000000000103',
     'Rebooked road test; scheduling system fix reported to IT.', now() - interval '4 days', now() - interval '6 days'),

    ('G0006', 'PORTAL', 'c0000000-0000-0000-0000-000000000101',
     'Someone registered a car in my name at an address I have never lived at. I only found out when I got a parking ticket for it.',
     'TITLE', 'TITLE', 'identity-fraud', NULL, 'CRITICAL', 0.82, 'NEGATIVE', -0.8,
     'ESCALATED', now() + interval '2 hours', 'TITLE', 'ee000000-0000-0000-0000-000000000108',
     NULL, NULL, now() - interval '2 hours'),

    ('G0007', 'PORTAL', 'c0000000-0000-0000-0000-000000000102',
     'The dealer gave me a 20-day plate a month ago and I still do not have my registration.',
     'DEALER', 'DEALER', 'dealer-paperwork', NULL, 'HIGH', 0.86, 'NEGATIVE', -0.6,
     'IN_PROGRESS', now() + interval '10 hours', 'DEALER', 'ee000000-0000-0000-0000-000000000110',
     NULL, NULL, now() - interval '14 hours'),

    ('G0008', 'PORTAL', NULL,
     'Thank you to the clerk at the Concord office who helped me with my REAL ID today.',
     NULL, NULL, NULL, NULL, NULL, 0.95, 'POSITIVE', 0.9,
     'NOT_ACTIONABLE', NULL, NULL, NULL,
     NULL, now() - interval '5 days', now() - interval '5 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note) VALUES
    ('b0000000-0000-0000-0000-000000000101', 'G0002', 'TRIAGED', 'RESOLVED', 'ee000000-0000-0000-0000-000000000107', now() - interval '2 days', 'Duplicate title application (TDMV 18) processed and mailed.'),
    ('b0000000-0000-0000-0000-000000000102', 'G0005', 'TRIAGED', 'RESOLVED', 'ee000000-0000-0000-0000-000000000103', now() - interval '4 days', 'Rebooked road test; scheduling system fix reported to IT.'),
    ('b0000000-0000-0000-0000-000000000103', 'G0006', 'TRIAGED', 'ESCALATED', 'ee000000-0000-0000-0000-000000000108', now() - interval '1 hour', 'Possible title/registration fraud; referred to anti-theft investigator.')
ON CONFLICT (id) DO NOTHING;

-- New submissions mint IDs from grievance_id_seq; move it past the seeded G0001-G0008 so the next
-- real grievance doesn't collide with one of them.
SELECT setval('grievance_id_seq', GREATEST((SELECT max(substring(id FROM 2)::int) FROM grievances), 1));
