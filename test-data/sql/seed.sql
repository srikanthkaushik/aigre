-- AIGRE systems-of-record seed data (plan §3.5)
-- Two distinct purposes mixed in this file, clearly separated below:
--   1. Operational/demo rows -- realistic mixed-status grievances for
--      dashboard and trend-analysis demo purposes.
--   2. Deliberate edge cases -- exist specifically to exercise MCP tool
--      error paths in milestone 3. Do not "fix" these into clean data.
--
-- Run against the aigre-pg database AFTER schema.sql (departments and
-- sla_policies are already seeded by schema.sql). ID scheme: employees use
-- prefix 'ee', citizens 'c0', operational grievances 'a0', edge-case
-- grievances 'af', status history 'b0' -- purely for readability when
-- inspecting rows; the letters carry no semantic meaning to Postgres.

--------------------------------------------------------------------------
-- Department employees (2 per department: 1 agent, 1 supervisor)
--------------------------------------------------------------------------

-- password_hash is the same bcrypt hash (cost 10) for every seeded employee, all sharing the
-- one demo password "Demo1234!" -- documented in RUNNING.md so the user can actually log in.
-- A real deployment would obviously never share one password across accounts; fine for a
-- single-instance demo where the point is exercising real Spring Security + JWT, not credential
-- hygiene.
INSERT INTO department_employees (id, department_id, name, role, username, password_hash) VALUES
    ('ee000000-0000-0000-0000-000000000001', 'DOT', 'Priya Nakamura', 'AGENT', 'priya.nakamura', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000002', 'DOT', 'Marcus Webb', 'SUPERVISOR', 'marcus.webb', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000003', 'DPW', 'Lena Ortiz', 'AGENT', 'lena.ortiz', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000004', 'DPW', 'Grant Okafor', 'SUPERVISOR', 'grant.okafor', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000005', 'DHHS', 'A. Sandoval', 'AGENT', 'a.sandoval', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000006', 'DHHS', 'R. Whitfield', 'SUPERVISOR', 'r.whitfield', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000007', 'DOE', 'Kayla Simmons', 'AGENT', 'kayla.simmons', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000008', 'DOE', 'Dennis Choi', 'SUPERVISOR', 'dennis.choi', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000009', 'DHUD', 'Priscilla Adeyemi', 'AGENT', 'priscilla.adeyemi', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000010', 'DHUD', 'Tom Reilly', 'SUPERVISOR', 'tom.reilly', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000011', 'DEP', 'Nora Fitzgerald', 'AGENT', 'nora.fitzgerald', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm'),
    ('ee000000-0000-0000-0000-000000000012', 'DEP', 'Sam Alvarez', 'SUPERVISOR', 'sam.alvarez', '$2a$10$jrCFQDXyDPNMB1IA3URfZOECM.Vl.CN91kOQ/Zr8.Fzgcjgx3vikm')
ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username, password_hash = EXCLUDED.password_hash;

--------------------------------------------------------------------------
-- Citizens (mixed completeness -- one deliberately missing all contact
-- info, used by the anonymous-grievance edge case below)
--------------------------------------------------------------------------

INSERT INTO citizens (id, name, email, phone, preferred_contact) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'Diane Marsh', 'diane.marsh@example.com', '555-010-2231', 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000002', 'Robert Chen', 'robert.chen@example.com', NULL, 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000003', 'Yolanda Perez', NULL, '555-010-7745', 'PHONE'),
    ('c0000000-0000-0000-0000-000000000004', 'Ahmed Hassan', 'ahmed.hassan@example.com', '555-010-9982', 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000005', 'Grace Liu', 'grace.liu@example.com', NULL, 'EMAIL'),
    ('c0000000-0000-0000-0000-000000000006', 'Marcus Bell', NULL, '555-010-4410', 'PHONE'),
    ('c0000000-0000-0000-0000-000000000007', 'Unknown Caller', NULL, NULL, NULL);
-- c...007 has no contact info at all -- pairs with the anonymous grievance
-- edge case below (in that case citizen_id is NULL entirely; this row
-- represents the separate case of a citizen record that exists but has no
-- usable contact method, e.g. captured from a phone call with a bad line).

--------------------------------------------------------------------------
-- 1. OPERATIONAL / DEMO ROWS -- realistic mixed-status grievances
--------------------------------------------------------------------------

INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, subcategory, priority, classification_confidence, sentiment_label, sentiment_score,
    status, sla_due_at, assigned_department, assigned_employee_id, resolution_notes, resolved_at, submitted_at
) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'PORTAL', 'c0000000-0000-0000-0000-000000000001',
     'There is a large pothole on Main Street that has been there for two weeks and is damaging cars.',
     'DOT', 'DOT', 'road-surface', NULL, 'MEDIUM', 0.92, 'NEGATIVE', -0.6,
     'RESOLVED', now() - interval '3 days', 'DOT', 'ee000000-0000-0000-0000-000000000001',
     'Patched within SLA.', now() - interval '4 days', now() - interval '9 days'),

    ('a0000000-0000-0000-0000-000000000002', 'PORTAL', 'c0000000-0000-0000-0000-000000000002',
     'The traffic signal at 4th and Elm has been stuck on red in all directions since this morning.',
     'DOT', 'DOT', 'traffic-signal', NULL, 'HIGH', 0.95, 'NEUTRAL', -0.1,
     'IN_PROGRESS', now() + interval '6 hours', 'DOT', 'ee000000-0000-0000-0000-000000000002',
     NULL, NULL, now() - interval '10 hours'),

    ('a0000000-0000-0000-0000-000000000003', 'PORTAL', 'c0000000-0000-0000-0000-000000000003',
     'A streetlight at the corner of 6th and Ash has been completely dark for about a week now.',
     'DPW', 'DPW', 'street-lighting', NULL, 'MEDIUM', 0.88, 'NEGATIVE', -0.4,
     'ROUTED', now() + interval '4 days', 'DPW', 'ee000000-0000-0000-0000-000000000003',
     NULL, NULL, now() - interval '1 day'),

    ('a0000000-0000-0000-0000-000000000004', 'PORTAL', 'c0000000-0000-0000-0000-000000000004',
     'There is water bubbling up out of the street on Chestnut Ave and it looks like it is getting worse.',
     'DPW', 'DPW', 'water-sewer', NULL, 'HIGH', 0.97, 'NEGATIVE', -0.7,
     'RESOLVED', now() - interval '2 days', 'DPW', 'ee000000-0000-0000-0000-000000000004',
     'Main isolated and repaired within 19 hours.', now() - interval '2 days', now() - interval '3 days'),

    ('a0000000-0000-0000-0000-000000000005', 'PORTAL', 'c0000000-0000-0000-0000-000000000005',
     'The restaurant on 2nd Ave has had mice visible near the front counter twice now that I have seen.',
     'DHHS', 'DHHS', 'food-safety', NULL, 'MEDIUM', 0.9, 'NEGATIVE', -0.5,
     'IN_PROGRESS', now() + interval '3 days', 'DHHS', 'ee000000-0000-0000-0000-000000000005',
     NULL, NULL, now() - interval '2 days'),

    ('a0000000-0000-0000-0000-000000000006', 'PORTAL', 'c0000000-0000-0000-0000-000000000006',
     'My son has been getting text messages from a classmate calling him names every night after school.',
     'DOE', 'DOE', 'bullying', NULL, 'MEDIUM', 0.85, 'NEGATIVE', -0.6,
     'ESCALATED', now() + interval '2 days', 'DOE', 'ee000000-0000-0000-0000-000000000008',
     'Escalated to supervisor for jurisdiction confirmation (off-campus conduct).', NULL, now() - interval '4 days'),

    ('a0000000-0000-0000-0000-000000000007', 'PORTAL', 'c0000000-0000-0000-0000-000000000001',
     'The elevator in our public housing building has been out for two days and my neighbor uses a wheelchair.',
     'DHUD', 'DHUD', 'public-housing', NULL, 'HIGH', 0.93, 'NEGATIVE', -0.5,
     'RESOLVED', now() - interval '1 day', 'DHUD', 'ee000000-0000-0000-0000-000000000009',
     'Vendor dispatched, repair completed within 30 hours, accommodation provided during outage.',
     now() - interval '1 day', now() - interval '3 days'),

    ('a0000000-0000-0000-0000-000000000008', 'PORTAL', 'c0000000-0000-0000-0000-000000000002',
     'There is a big pile of what looks like old furniture and drywall dumped in the empty lot behind my house.',
     'DEP', 'DEP', 'illegal-dumping', NULL, 'MEDIUM', 0.91, 'NEGATIVE', -0.4,
     'CLOSED', now() - interval '10 days', 'DEP', 'ee000000-0000-0000-0000-000000000011',
     'Removed, violation citation issued to responsible contractor.', now() - interval '11 days', now() - interval '16 days'),

    ('a0000000-0000-0000-0000-000000000009', 'PORTAL', 'c0000000-0000-0000-0000-000000000003',
     'My recycling was not picked up today even though I put it out on time like every other week.',
     'DPW', NULL, 'trash-collection', NULL, 'MEDIUM', 0.87, 'NEGATIVE', -0.3,
     'NEW', now() + interval '2 days', NULL, NULL, NULL, NULL, now() - interval '3 hours'),

    ('a0000000-0000-0000-0000-000000000010', 'PORTAL', 'c0000000-0000-0000-0000-000000000004',
     'Great job on the new bike lane downtown, it has been really nice to use this month.',
     NULL, NULL, NULL, NULL, NULL, NULL, 'POSITIVE', 0.8,
     'NOT_ACTIONABLE', NULL, NULL, NULL, 'Compliment, no action needed.', now() - interval '5 days', now() - interval '5 days');

--------------------------------------------------------------------------
-- 2. DELIBERATE EDGE CASES -- exercise MCP tool error paths (milestone 3)
--------------------------------------------------------------------------

-- Edge case A: bad department code. department_predicted/assigned_department
-- reference 'DMV', a department that does not exist in the departments
-- table -- possible only because these columns are deliberately NOT foreign
-- keys (see schema.sql comment). Represents a legacy row from before a
-- department was renamed/decommissioned.
INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, priority, classification_confidence, status, sla_due_at,
    assigned_department, submitted_at
) VALUES (
    'af000000-0000-0000-0000-000000000001', 'PORTAL', 'c0000000-0000-0000-0000-000000000005',
    'Legacy complaint about a vehicle registration issue, filed before the DMV function was absorbed into DOT.',
    'DMV', 'DMV', 'vehicle-registration', 'LOW', 0.6, 'ROUTED', now() - interval '200 days',
    'DMV', now() - interval '210 days'
);

-- Edge case B: stale breach. Stuck IN_PROGRESS with sla_due_at far in the
-- past and never escalated -- a pipeline-failure case the SLA-breach scan
-- (plan scenario 6) should have caught and didn't.
INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, priority, classification_confidence, status, sla_due_at,
    assigned_department, assigned_employee_id, submitted_at
) VALUES (
    'af000000-0000-0000-0000-000000000002', 'PORTAL', 'c0000000-0000-0000-0000-000000000006',
    'A stop sign at the corner of Poplar and 9th appears to have been knocked over, possibly hit by a car.',
    'DOT', 'DOT', 'street-signage', 'HIGH', 0.9, 'IN_PROGRESS', now() - interval '45 days',
    'DOT', 'ee000000-0000-0000-0000-000000000001', now() - interval '46 days'
);

-- Edge case C: duplicate chain, two hops deep (A -> B -> C), not just a
-- single link. Tests that dedup/duplicate-lookup tools walk the whole
-- chain rather than assuming one level of indirection.
INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, priority, classification_confidence, status, sla_due_at,
    assigned_department, duplicate_of_id, submitted_at
) VALUES
    ('af000000-0000-0000-0000-000000000003', 'PORTAL', 'c0000000-0000-0000-0000-000000000001',
     'There is a pothole at the corner of 5th and Birch that is about 3 inches deep and causing cars to swerve.',
     'DOT', 'DOT', 'road-surface', 'MEDIUM', 0.92, 'TRIAGED', now() + interval '2 days',
     'DOT', NULL, now() - interval '6 days'),
    ('af000000-0000-0000-0000-000000000004', 'PORTAL', 'c0000000-0000-0000-0000-000000000002',
     'Just wanted to report a pothole on 5th Ave near Birch St, pretty deep, cars are swerving around it.',
     'DOT', 'DOT', 'road-surface', 'MEDIUM', 0.9, 'DUPLICATE', NULL,
     'DOT', 'af000000-0000-0000-0000-000000000003', now() - interval '4 days'),
    ('af000000-0000-0000-0000-000000000005', 'PORTAL', 'c0000000-0000-0000-0000-000000000003',
     'Is anyone aware of the pothole at 5th and Birch? It has been there a while and no one has fixed it.',
     'DOT', 'DOT', 'road-surface', 'MEDIUM', 0.88, 'DUPLICATE', NULL,
     'DOT', 'af000000-0000-0000-0000-000000000004', now() - interval '1 day');
-- Note: row 5's duplicate_of_id points to row 4, not directly to row 3 -- a
-- duplicate-lookup tool that only follows one level will incorrectly
-- treat row 5 as a duplicate of row 4 rather than resolving the chain back
-- to the true original, row 3.

-- Edge case D: anonymous grievance, no citizen record at all (citizen_id
-- NULL) -- cannot notify the submitter of status changes. Distinct from
-- citizen c...007 above, which has a citizen record but no usable contact
-- method.
INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, priority, classification_confidence, status, sla_due_at,
    assigned_department, submitted_at
) VALUES (
    'af000000-0000-0000-0000-000000000006', 'PORTAL', NULL,
    'There is a dead raccoon in the middle of the street on Hawthorne Ave, it has been there since yesterday.',
    'DPW', 'DPW', 'trash-collection', 'MEDIUM', 0.8, 'TRIAGED', now() + interval '2 days',
    'DPW', now() - interval '1 day'
);

-- Edge case E: never classified. classification_confidence and
-- department_predicted are both NULL, status stuck at NEW -- represents a
-- pipeline failure where intake succeeded but classification never ran.
INSERT INTO grievances (
    id, channel, citizen_id, raw_text, department_predicted, department_confirmed,
    category, priority, classification_confidence, status, sla_due_at,
    assigned_department, submitted_at
) VALUES (
    'af000000-0000-0000-0000-000000000007', 'PORTAL', 'c0000000-0000-0000-0000-000000000007',
    'Something is wrong and I need help with it as soon as possible please.',
    NULL, NULL, NULL, NULL, NULL, 'NEW', NULL,
    NULL, now() - interval '2 hours'
);

--------------------------------------------------------------------------
-- Status history for a representative subset (not exhaustive -- enough to
-- exercise the audit-trail read path)
--------------------------------------------------------------------------

INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note) VALUES
    ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'NEW', 'system:intake', now() - interval '9 days', NULL),
    ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'NEW', 'TRIAGED', 'system:classifier', now() - interval '9 days', NULL),
    ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'TRIAGED', 'RESOLVED', 'ee000000-0000-0000-0000-000000000001', now() - interval '4 days', 'Patched within SLA.'),
    ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000006', 'TRIAGED', 'ESCALATED', 'ee000000-0000-0000-0000-000000000008', now() - interval '2 days', 'Jurisdiction confirmation needed for off-campus conduct.'),
    ('b0000000-0000-0000-0000-000000000005', 'af000000-0000-0000-0000-000000000002', 'TRIAGED', 'IN_PROGRESS', 'ee000000-0000-0000-0000-000000000001', now() - interval '44 days', 'Assigned to field crew.');
-- Note: af...0002 (the stale-breach edge case) has no further history after
-- IN_PROGRESS -- that gap is the point of the edge case.
