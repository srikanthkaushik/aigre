# Corpus Manifest

**108 of the 100–150 target documents (plan §3.3)** — tranche 1 (38 docs,
below) plus tranche 2 (70 docs, in the second table further down). Covers
all 62 eval questions in `eval-questions.md` (EQ-001–062). Target reached;
further growth is optional polish, not required to satisfy the spec.

## Tranche 1 (38 documents)

Department folder name = `department` metadata tag applied at ingestion
(`CorpusIngestionService.attachDepartmentMetadata`). `SHARED` is not a real
department — it tags citywide/cross-department policy.

| # | File | Department | Doc type | Effective date | Superseded by | Answers / distracts |
|---|---|---|---|---|---|---|
| 1 | DOT/road-maintenance-sop-v1-superseded.txt | DOT | SOP (superseded) | 2023-03-01 | v2 (2026-01-15) | EQ-033; superseded-context for EQ-031, EQ-032 |
| 2 | DOT/road-maintenance-sop-v2-current.txt | DOT | SOP (current) | 2026-01-15 | — | EQ-001, EQ-031, EQ-032, EQ-035 (negative), EQ-039 (negative); distractor target for EQ-022, EQ-026 |
| 3 | DOT/traffic-signal-policy.txt | DOT | Policy | 2025-06-01 | — | EQ-002, EQ-030, EQ-046/047 (escalation context) |
| 4 | DOT/public-transit-faq.txt | DOT | FAQ | 2026-02 (last updated) | — | EQ-003 |
| 5 | DOT/street-signage-policy.txt | DOT | Policy | 2024-09-01 | — | EQ-004, EQ-039 (negative — crosswalk repainting not tracked) |
| 6 | DOT/resolved-cases-q1-2026.txt | DOT | Resolved-case log | 2026 Q1 | — | Chatbot "how was this resolved" context; duplicate-handling example |
| 7 | DPW/sidewalk-maintenance-policy.txt | DPW | Policy | 2024-04-01 | — | EQ-022 (correct answer, distracts against DOT SOP v2) |
| 8 | DPW/water-main-break-sop.txt | DPW | SOP | 2023-05-01 | — | EQ-006; distractor target for EQ-023 |
| 9 | DPW/street-lighting-policy.txt | DPW | Policy | 2024-11-01 | — | EQ-005 |
| 10 | DPW/trash-collection-sop.txt | DPW | SOP | 2025-01-01 | — | EQ-007; distractor target for EQ-024 |
| 11 | DPW/snow-removal-policy.txt | DPW | Policy | 2023-10-01 | — | EQ-008, EQ-026 (correct answer) |
| 12 | DPW/public-works-faq.txt | DPW | FAQ | 2026-01 (last updated) | — | EQ-027 (tree/right-of-way section) |
| 13 | DPW/resolved-cases-q1-2026.txt | DPW | Resolved-case log | 2026 Q1 | — | Re-routing example (missed collection -> illegal dumping) |
| 14 | DHHS/mandatory-reporting-guide.txt | DHHS | Policy | 2022-07-01 | — | EQ-009 |
| 15 | DHHS/food-safety-sop.txt | DHHS | SOP | 2024-03-01 | — | EQ-010; distractor target for EQ-025 |
| 16 | DHHS/benefits-eligibility-faq.txt | DHHS | FAQ | 2025-12 (last updated) | — | EQ-011, EQ-037 (negative — state benefits out of scope) |
| 17 | DHHS/elder-adult-protective-services-guide.txt | DHHS | Policy | 2023-08-01 | — | EQ-012; distractor target for EQ-028 |
| 18 | DHHS/resolved-cases-q1-2026.txt | DHHS | Resolved-case log | 2026 Q1 | — | Chatbot context |
| 19 | DOE/student-safety-antibullying-policy.txt | DOE | Policy | 2023-09-01 | — | EQ-013; distractor target for EQ-030 |
| 20 | DOE/school-facilities-sop.txt | DOE | SOP | 2024-06-01 | — | EQ-014, EQ-045 (playground fence dual-tag) |
| 21 | DOE/special-education-faq.txt | DOE | FAQ | 2025-10 (last updated) | — | EQ-015 |
| 22 | DOE/school-health-services-guide.txt | DOE | Policy | 2025-02-01 | — | EQ-025 (correct answer, distracts against DHHS food safety) |
| 23 | DOE/resolved-cases-q1-2026.txt | DOE | Resolved-case log | 2026 Q1 | — | Chatbot context; dual-tag example |
| 24 | DHUD/tenant-complaint-code-enforcement-guide.txt | DHUD | Policy | 2024-05-01 | — | EQ-016, EQ-038 (negative — homeowner disputes out of scope) |
| 25 | DHUD/tenant-utility-complaint-guide.txt | DHUD | Policy | 2024-05-01 | — | EQ-023 (correct answer, distracts against DPW water main SOP) |
| 26 | DHUD/public-housing-maintenance-sop.txt | DHUD | SOP | 2025-01-01 | — | EQ-017, EQ-029 (correct answer, distracts against DEP air quality); EQ-048 context |
| 27 | DHUD/homelessness-services-faq.txt | DHUD | FAQ | 2025-11 (last updated) | — | EQ-018 |
| 28 | DHUD/resolved-cases-q1-2026.txt | DHUD | Resolved-case log | 2026 Q1 | — | Chatbot context; DPW cross-check example |
| 29 | DEP/illegal-dumping-policy.txt | DEP | Policy | 2024-04-01 | — | EQ-020, EQ-024 (correct answer) |
| 30 | DEP/noise-ordinance-policy.txt | DEP | Policy | 2023-07-01 | — | EQ-019, EQ-028 (correct answer) |
| 31 | DEP/air-quality-sop.txt | DEP | SOP | 2024-02-01 | — | EQ-021; distractor target for EQ-027, EQ-029 |
| 32 | DEP/environmental-complaints-faq.txt | DEP | FAQ | 2026-01 (last updated) | — | Reinforces EQ-020, EQ-028, EQ-029 distinctions |
| 33 | DEP/resolved-cases-q1-2026.txt | DEP | Resolved-case log | 2026 Q1 | — | Re-routing example (elder-welfare framing -> noise) |
| 34 | SHARED/escalation-safety-flag-policy.txt | SHARED | Policy | 2025-01-01 | — | EQ-041–047, EQ-050 (hazard criteria, sentiment-vs-hazard distinction) |
| 35 | SHARED/jurisdiction-out-of-scope-guide.txt | SHARED | Policy | 2025-01-01 | — | EQ-034, EQ-036, EQ-037, EQ-038, EQ-040 (negatives) |
| 36 | SHARED/sla-policy-summary.txt | SHARED | Policy | 2025-01-01 | — | Cross-checks priority-rubric questions; EQ-049 (bump rule) |
| 37 | SHARED/privacy-pii-notice.txt | SHARED | Policy | 2025-01-01 | — | EQ-041–044 |
| 38 | SHARED/appeals-process.txt | SHARED | Policy | 2025-01-01 | — | Appeals-adjacent context, distinguishes from DHHS benefits appeal |

## Tranche 2 (70 documents — brings total to 108)

Adds: 2 more resolved-case logs per department (Q4 2025, Q2 2026), a
general-intake FAQ per department, several new SOPs/policies/FAQs
introducing new distractor pairs (EQ-051–055), a second superseded pair
(DHHS benefits appeals, EQ-056/057), 2 new negatives (EQ-058/059), and 3
new classification/routing questions (EQ-060–062).

| # | File | Department | Doc type | Effective date | Superseded by | Answers / distracts |
|---|---|---|---|---|---|---|
| 39 | DOT/bike-lane-maintenance-policy.txt | DOT | Policy | 2025-05-01 | — | Volume; cross-referenced by pavement marking/signage docs |
| 40 | DOT/school-zone-safety-program.txt | DOT | Policy | 2024-08-01 | — | Expands EQ-030 context (crossing guard/signal coordination) |
| 41 | DOT/winter-road-treatment-sop.txt | DOT | SOP | 2024-10-01 | — | Distinguishes from DPW snow removal (proactive vs. reactive) |
| 42 | DOT/pavement-marking-program.txt | DOT | Policy | 2025-03-01 | — | Reinforces EQ-039 negative (no per-report crosswalk SLA) |
| 43 | DOT/traffic-calming-request-guide.txt | DOT | FAQ | 2025-04 (last updated) | — | Volume; distinguishes planning request from complaint |
| 44 | DOT/railroad-crossing-safety-policy.txt | DOT | Policy | 2024-01-01 | — | Volume; jurisdiction nuance (railroad-owned equipment) |
| 45 | DOT/vehicle-for-hire-complaint-guide.txt | DOT | FAQ | 2025-06 (last updated) | — | EQ-060 (nuanced out-of-scope) |
| 46 | DOT/general-intake-faq.txt | DOT | FAQ | 2026-07 (last updated) | — | General intake context |
| 47 | DOT/resolved-cases-q4-2025.txt | DOT | Resolved-case log | 2025 Q4 | — | Chatbot context |
| 48 | DOT/resolved-cases-q2-2026.txt | DOT | Resolved-case log | 2026 Q2 | — | Chatbot context |
| 49 | DPW/urban-forestry-policy.txt | DPW | Policy | 2024-03-01 | — | EQ-052 (distractor pair with DEP tree preservation) |
| 50 | DPW/graffiti-removal-policy.txt | DPW | Policy | 2024-06-01 | — | Volume |
| 51 | DPW/bulk-item-pickup-sop.txt | DPW | SOP | 2025-02-01 | — | Volume; distinguishes from DEP illegal dumping |
| 52 | DPW/animal-control-removal-sop.txt | DPW | SOP | 2025-04-01 | — | Volume |
| 53 | DPW/storm-drain-maintenance-sop.txt | DPW | SOP | 2024-09-01 | — | Distinguishes from DEP wetlands/stormwater (mechanical vs. contamination) |
| 54 | DPW/facilities-maintenance-policy.txt | DPW | Policy | 2024-11-01 | — | Three-way distractor with DOE/DHUD facilities docs |
| 55 | DPW/general-intake-faq.txt | DPW | FAQ | 2026-07 (last updated) | — | General intake context |
| 56 | DPW/fleet-equipment-policy.txt | DPW | Policy (internal) | 2025-01-01 | — | Volume; internal-doc realism |
| 57 | DPW/resolved-cases-q4-2025.txt | DPW | Resolved-case log | 2025 Q4 | — | Chatbot context |
| 58 | DPW/resolved-cases-q2-2026.txt | DPW | Resolved-case log | 2026 Q2 | — | Dual-tag example (protected tree) |
| 59 | DHHS/benefits-appeals-policy-v1-superseded.txt | DHHS | Policy (superseded) | 2022-01-01 | v2 (2026-02-01) | EQ-057 |
| 60 | DHHS/benefits-appeals-policy-v2-current.txt | DHHS | Policy (current) | 2026-02-01 | — | EQ-056 |
| 61 | DHHS/senior-nutrition-meal-services-faq.txt | DHHS | FAQ | 2025-09 (last updated) | — | Volume |
| 62 | DHHS/public-health-nuisance-inspection-sop.txt | DHHS | SOP | 2024-05-01 | — | Volume; distinguishes from food-safety SOP |
| 63 | DHHS/immunization-clinic-access-faq.txt | DHHS | FAQ | 2025-08 (last updated) | — | Volume |
| 64 | DHHS/mental-health-crisis-referral-guide.txt | DHHS | Policy | 2024-10-01 | — | Volume; crisis-handling nuance |
| 65 | DHHS/homeless-outreach-coordination-guide.txt | DHHS | Policy | 2024-12-01 | — | EQ-053 (legitimate overlap with DHUD) |
| 66 | DHHS/child-care-licensing-complaint-sop.txt | DHHS | SOP | 2025-07-01 | — | Volume; distinguishes from DOE teacher conduct |
| 67 | DHHS/general-intake-faq.txt | DHHS | FAQ | 2026-07 (last updated) | — | General intake context |
| 68 | DHHS/domestic-violence-resource-referral-guide.txt | DHHS | Policy | 2025-03-01 | — | Volume; confidentiality nuance |
| 69 | DHHS/substance-abuse-referral-faq.txt | DHHS | FAQ | 2026-02 (last updated) | — | Volume |
| 70 | DHHS/resolved-cases-q4-2025.txt | DHHS | Resolved-case log | 2025 Q4 | — | Chatbot context |
| 71 | DHHS/resolved-cases-q2-2026.txt | DHHS | Resolved-case log | 2026 Q2 | — | Dual-tag example (DHUD coordination) |
| 72 | DOE/school-transportation-policy.txt | DOE | Policy | 2024-08-01 | — | Distinguishes from DOT transit and special-ed transport |
| 73 | DOE/enrollment-boundary-dispute-faq.txt | DOE | FAQ | 2025-05 (last updated) | — | Volume |
| 74 | DOE/free-reduced-lunch-faq.txt | DOE | FAQ | 2025-09 (last updated) | — | EQ-054 (distractor pair with DHHS benefits) |
| 75 | DOE/after-school-program-complaint-sop.txt | DOE | SOP | 2025-10-01 | — | Volume |
| 76 | DOE/teacher-conduct-complaint-policy.txt | DOE | Policy | 2025-01-01 | — | EQ-062 (distractor pair with anti-bullying policy) |
| 77 | DOE/accessibility-ada-policy.txt | DOE | Policy | 2025-04-01 | — | Volume; distinguishes physical ADA from IEP services |
| 78 | DOE/truancy-attendance-policy.txt | DOE | Policy | 2024-09-01 | — | Volume; mandatory-reporting cross-reference |
| 79 | DOE/general-intake-faq.txt | DOE | FAQ | 2026-07 (last updated) | — | General intake context |
| 80 | DOE/student-records-privacy-policy.txt | DOE | Policy | 2025-06-01 | — | Volume; FERPA/privacy nuance |
| 81 | DOE/resolved-cases-q4-2025.txt | DOE | Resolved-case log | 2025 Q4 | — | Chatbot context |
| 82 | DOE/resolved-cases-q2-2026.txt | DOE | Resolved-case log | 2026 Q2 | — | Chatbot context |
| 83 | DHUD/rental-mediation-program-faq.txt | DHUD | FAQ | 2025-04 (last updated) | — | Volume; market-rate vs. subsidized distinction |
| 84 | DHUD/housing-choice-voucher-faq.txt | DHUD | FAQ | 2025-06 (last updated) | — | Volume |
| 85 | DHUD/first-time-homebuyer-faq.txt | DHUD | FAQ | 2025-03 (last updated) | — | EQ-058 (negative — refinancing out of scope) |
| 86 | DHUD/fair-housing-discrimination-sop.txt | DHUD | SOP | 2025-02-01 | — | Volume |
| 87 | DHUD/public-housing-waitlist-faq.txt | DHUD | FAQ | 2026-01 (last updated) | — | Volume |
| 88 | DHUD/lead-paint-inspection-sop.txt | DHUD | SOP | 2025-05-01 | — | Volume; DHHS cross-reference |
| 89 | DHUD/general-intake-faq.txt | DHUD | FAQ | 2026-07 (last updated) | — | General intake context |
| 90 | DHUD/accessibility-modification-guide.txt | DHUD | Policy | 2025-07-01 | — | Volume |
| 91 | DHUD/eviction-prevention-faq.txt | DHUD | FAQ | 2026-02 (last updated) | — | Volume; distinguishes from shelter referral |
| 92 | DHUD/resolved-cases-q4-2025.txt | DHUD | Resolved-case log | 2025 Q4 | — | Dual-tag example (DHHS lead screening) |
| 93 | DHUD/resolved-cases-q2-2026.txt | DHUD | Resolved-case log | 2026 Q2 | — | Chatbot context |
| 94 | DEP/water-quality-testing-sop.txt | DEP | SOP | 2024-06-01 | — | EQ-051 (distractor pair with DPW water main) |
| 95 | DEP/recycling-composting-program-faq.txt | DEP | FAQ | 2025-10 (last updated) | — | EQ-055 (distractor pair with DPW trash collection) |
| 96 | DEP/pesticide-herbicide-complaint-sop.txt | DEP | SOP | 2025-04-01 | — | Volume |
| 97 | DEP/wetlands-stormwater-policy.txt | DEP | Policy | 2024-08-01 | — | Volume; distinguishes from DPW storm drain SOP |
| 98 | DEP/hazardous-waste-disposal-faq.txt | DEP | FAQ | 2025-11 (last updated) | — | Volume; distinguishes from illegal dumping |
| 99 | DEP/tree-preservation-policy.txt | DEP | Policy | 2024-09-01 | — | EQ-052 (distractor pair with DPW urban forestry) |
| 100 | DEP/general-intake-faq.txt | DEP | FAQ | 2026-07 (last updated) | — | General intake context |
| 101 | DEP/vehicle-emissions-idling-sop.txt | DEP | SOP | 2025-01-01 | — | Volume |
| 102 | DEP/construction-dust-erosion-sop.txt | DEP | SOP | 2025-03-01 | — | EQ-061 |
| 103 | DEP/resolved-cases-q4-2025.txt | DEP | Resolved-case log | 2025 Q4 | — | Dual-tag example (protected tree) |
| 104 | DEP/resolved-cases-q2-2026.txt | DEP | Resolved-case log | 2026 Q2 | — | Chatbot context |
| 105 | SHARED/accessibility-language-access-policy.txt | SHARED | Policy | 2025-01-01 | — | Volume; portal-level accessibility |
| 106 | SHARED/multi-department-coordination-protocol.txt | SHARED | Policy | 2025-01-01 | — | Formalizes dual-tag mechanics referenced across tranche-2 docs |
| 107 | SHARED/trend-analysis-policy.txt | SHARED | Policy | 2025-01-01 | — | Ties to trend-analysis business goal |
| 108 | SHARED/citizen-notification-policy.txt | SHARED | Policy | 2025-01-01 | — | Volume; notification-preference nuance |

## Coverage check

All 62 eval questions in `eval-questions.md` (EQ-001 through EQ-062) have
at least one ground-truth or distractor document above. Categories B, D,
and G's distractor sub-cases depend on *pairs* of documents — verified
each pair exists in the tables. Live retrieval verification: see
PROJECT.md's "Eval suite" section for the `RagEvalSuiteTest` results
against the tranche-1 subset (tranche-2 questions EQ-051+ not yet run
through the live eval suite — see PROJECT.md open items).

## Remaining headroom (optional, not required to hit spec)

108 comfortably clears the 100–150 target floor. Further growth (toward
130–150) would mean: more resolved-case entries, more within-department
distractor pairs, or a third superseded-version pair — none required to
satisfy plan §3.3.
