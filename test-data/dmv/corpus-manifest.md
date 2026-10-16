# DMV corpus manifest (dmv domain profile)

The policy corpus the `dmv` profile ingests (`rag.ingest.corpus-path: test-data/dmv/documents`).
Each folder is one routing unit. The folder name is the `departments.id` the ingested chunks
are tagged with, so retrieval can scope to a bureau. `SHARED/` holds cross-bureau rules and
is never a routing target.

Every document here is a chapter of the **New Hampshire Code of Administrative Rules,
Department of Safety (Saf-C)**. Each was converted from the official HTML published by the
NH General Court, `https://gc.nh.gov/rules/state_agencies/saf-c<N>.html`, to plain text,
with paragraphs unwrapped and nothing else edited. The bureau structure follows Saf-C
102.08(c).

| Folder | Bureau | File | Saf-C chapter |
|---|---|---|---|
| `REG/` | Registration Bureau | `SAF-C-500.txt` | 500 Vehicle Registration |
| | | `SAF-C-3000.txt` | 3000 Hardship Registration |
| | | `SAF-C-1700.txt` | 1700 International Registration Plan |
| `DL/` | Driver Licensing Bureau | `SAF-C-1000.txt` | 1000 Driver Licensing |
| | | `SAF-C-1800.txt` | 1800 Commercial Driver Licensing |
| | | `SAF-C-6500.txt` | 6500 Ignition Interlock Program |
| `FR/` | Financial Responsibility Administration Bureau | `SAF-C-7500.txt` | 7500 Financial Responsibility |
| | | `SAF-C-1500.txt` | 1500 Restoration Fee Waiver |
| | | `SAF-C-2800.txt` | 2800 Administrative License Suspension |
| `TITLE/` | Bureau of Certificate of Title and Anti-Theft | `SAF-C-1900.txt` | 1900 Bureau of Title and Anti-Theft Rules |
| `DEALER/` | Dealer Bureau (dealer + inspection station sections) | `SAF-C-2000.txt` | 2000 Dealer Registration |
| | | `SAF-C-3200.txt` | 3200 Official Motor Vehicle Inspection Requirements |
| `SHARED/` | *(all bureaus)* | `SAF-C-5600.txt` | 5600 Confidentiality of Motor Vehicle Records |
| | | `SAF-C-6200.txt` | 6200 Administrative Fees |
| | | `SAF-C-200.txt` | 200 Procedural and Hearings Rules |
| | | `SAF-C-100.txt` | 100 Organizational Rules |

Retrieved October 2026. Rules are amended over time, so re-download a chapter to refresh it.

## Adding documents later

The corpus is rules only for now. Plain-language material (the DMV's title handbook,
driver's manual, fee sheets) would improve answers, but dmv.nh.gov blocks scripted
downloads, so it has to be fetched in a browser. To add any document:

1. Drop it into the bureau's folder (or `SHARED/`). Any format Apache Tika parses works:
   `.pdf`, `.txt`, `.docx`, `.html`.
2. Add a row to the table above with its source.
3. Re-run ingestion with an ADMIN token:
   `POST /ingest/reset?confirm=true` (see `docs/RUNNING.md`). It rebuilds the whole
   corpus.

A new **bureau** is a new folder plus a row in `src/main/resources/seed/dmv/departments.sql`,
or a live `POST /admin/departments` (see `docs/DEPARTMENT_ONBOARDING.md`).
