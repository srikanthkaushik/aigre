export interface GrievanceIntakeRequest {
  rawText: string;
  citizenName?: string | null;
  citizenEmail?: string | null;
  citizenPhone?: string | null;
}

// Mirrors com.aigre.workflow.ClarificationEntry exactly -- one citizen follow-up detail, kept
// distinct from the original grievance.raw_text (see GrievanceWorkflowService.clarify()).
export interface ClarificationEntry {
  text: string;
  submittedAt: string;
}

export interface GrievanceWorkflowResponse {
  grievanceId: string;
  status: string;
  pendingReview: boolean;
  department: string | null;
  category: string | null;
  priority: string | null;
  confidence: number;
  slaDueAt: string | null;
  reasoning: string | null;
  rawText: string | null;
  clarifications: ClarificationEntry[];
}

export interface GrievanceStatusResult {
  id: string;
  status: string;
  departmentPredicted: string | null;
  departmentConfirmed: string | null;
  departmentValid: boolean;
  category: string | null;
  priority: string | null;
  classificationConfidence: number | null;
  sentimentLabel: string | null;
  slaDueAt: string | null;
  submittedAt: string;
  resolvedAt: string | null;
  resolutionNotes: string | null;
  citizenContactAvailable: boolean;
}

export interface GrievanceSummary {
  id: string;
  status: string;
  department: string | null;
  category: string | null;
  priority: string | null;
  classificationConfidence: number | null;
  slaDueAt: string | null;
  submittedAt: string;
  resolutionNotes: string | null;
  breached: boolean;
}

export interface GrievanceReviewDecision {
  department?: string | null;
  category?: string | null;
  priority?: string | null;
  note: string;
  reviewedBy: string;
}

// Mirrors com.aigre.retrieval.RetrievedSource exactly -- text/metadata/vectorScore/rerankScore,
// not a flattened source/department/docType/score shape. metadata currently carries "source"
// (the source filename) and "department", both set during corpus ingestion.
export interface RetrievedSource {
  text: string;
  metadata: Record<string, unknown>;
  vectorScore: number;
  rerankScore: number;
}

export const DEPARTMENTS = ['DOT', 'DPW', 'DHHS', 'DOE', 'DHUD', 'DEP'] as const;
export type Department = (typeof DEPARTMENTS)[number];

// Mirrors schema.sql's departments seed data -- kept in sync by hand since the frontend has no
// live "list departments" endpoint to fetch this from.
export const DEPARTMENT_NAMES: Record<string, string> = {
  DOT: 'Department of Transportation',
  DPW: 'Department of Public Works',
  DHHS: 'Department of Health and Human Services',
  DOE: 'Department of Education',
  DHUD: 'Department of Housing and Urban Development',
  DEP: 'Department of Environmental Protection'
};

export const PRIORITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;

// Mirrors com.aigre.query.TrendsResponse and its nested records exactly -- verified against the
// live GET /grievances/trends response, not assumed (see the RetrievedSource mismatch bug
// earlier in this project for why that matters).
export interface DailyCount {
  date: string;
  count: number;
}

export interface DailySentiment {
  date: string;
  avgSentiment: number;
}

export interface CategoryCount {
  category: string;
  count: number;
}

export interface PriorityCount {
  priority: string;
  count: number;
}

export interface SlaSnapshot {
  resolvedOnTime: number;
  resolvedLate: number;
  currentlyBreachedOpen: number;
}

export interface TrendsResponse {
  volumeByDay: DailyCount[];
  byCategory: CategoryCount[];
  byPriority: PriorityCount[];
  sentimentByDay: DailySentiment[];
  slaSnapshot: SlaSnapshot;
}
