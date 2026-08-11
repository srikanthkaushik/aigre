Starting a new build. Read new-project-instructions.md first.

USE CASE: This is a public sector G2C AI Grievance Resolution Engine. It is an AI powered platform to automate, prioritize and resolve citizen complaints efficiently using NLP and predictive analytics, improving governance transparency and service delivery for public sector organizations. Various public sector organizations receive thousands of citizen complaints daily across mutiple channels such as emails, portal and social media. These complaints are often unstructured, inconsistently classified, and routed manually to the various departments leading to delays and inefficiencies in resolution. The absence of intelligent prioritization results in critical issues (e.g: safety hazards, service outages etc.) not being promptly addressed. Lack of analytics makes it difficult to identify reccurring problems or systemic failures leading to increased citizen dissatisfaction due to lack of visibility, delayed responses and poor communication.

BUSINESS WORKFLOW WE NEED TO BUILD:
AI based prioritization and routing to departments.
Chatbot for citizen interaction and status tracking
Trend analysis to identify recurring issues
Sentiment analysis for service quality management.
Complaint ingestion from 1-2 channels (portal/email). We will focus first on portal development and then pick up email ingestion as a separate workflow.
NLP based classification and priority assignment.
Dashboard showing complaint trends and SLA breaches.

END USERS: 
1. Citizens access to portal.
2. Departement employees with access to data for their particular departments and dashboards.


DOCUMENT CORPUS: various complaints from citizens spanning at least 6 departments. Some departments could be Department of Transportation (DOT), Department of Health and Human Services (DHHS), Department of Education (DOE). Create at least 3 more departments.

PROVIDER: starting on <anthropic | ollama>

I want three things in this first pass:
1. Milestone 0 plan — domain model covering the scenarios and their
   branching conditions, and a written definition of what "correct" means for
   a document checklist
2. The test-data spec per §3, including the eval questions BEFORE the
   documents that answer them. The documents should function as
   deliberate distractors for each other.
3. The day-one scaffold checklist per §2, adapted to this domain

Give me the plan first. Don't write code until I've agreed the domain model.

Use Plan Mode for this — I want to agree the domain model before any files
are created.