import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  GrievanceIntakeRequest,
  GrievanceReviewDecision,
  GrievanceStatusResult,
  GrievanceSummary,
  GrievanceWorkflowResponse,
  ReopenResult,
  RetrievedSource,
  TrendsResponse,
  UpdateStatusResult
} from './models';

// Relative, not absolute -- lets the same build work whether the browser reaches this app via
// localhost:4200 (ng serve, proxied to the backend by proxy.conf.json) or via the backend itself
// serving the built frontend as static content on a single origin (see SpaWebFluxConfig), which
// is also what makes tunneling just one port (the backend's) enough to expose the whole app.
const API_BASE = '';

export interface ChatStreamCallbacks {
  onToken: (token: string) => void;
  onSources: (sources: RetrievedSource[]) => void;
  onError: (message: string) => void;
  onDone: () => void;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private readonly http: HttpClient) {}

  submitGrievance(request: GrievanceIntakeRequest): Observable<GrievanceWorkflowResponse> {
    return this.http.post<GrievanceWorkflowResponse>(`${API_BASE}/grievances/workflow`, request);
  }

  resumeReview(grievanceId: string, decision: GrievanceReviewDecision): Observable<GrievanceWorkflowResponse> {
    return this.http.post<GrievanceWorkflowResponse>(
      `${API_BASE}/grievances/${grievanceId}/workflow/resume`,
      decision
    );
  }

  getWorkflowStatus(grievanceId: string): Observable<GrievanceWorkflowResponse> {
    return this.http.get<GrievanceWorkflowResponse>(`${API_BASE}/grievances/${grievanceId}/workflow`);
  }

  clarify(grievanceId: string, additionalText: string): Observable<GrievanceWorkflowResponse> {
    return this.http.post<GrievanceWorkflowResponse>(`${API_BASE}/grievances/${grievanceId}/workflow/clarify`, {
      additionalText
    });
  }

  getStatus(grievanceId: string): Observable<GrievanceStatusResult> {
    return this.http.get<GrievanceStatusResult>(`${API_BASE}/grievances/${grievanceId}`);
  }

  /**
   * Department is normally never client-supplied -- GrievanceQueryController derives it from the
   * authenticated employee's own token. The `department` param here is only honored server-side
   * for the ADMIN role (its cross-department filter); for any other role the backend ignores it.
   */
  listGrievances(status?: string | null, department?: string | null): Observable<GrievanceSummary[]> {
    const params: string[] = [];
    if (status) params.push(`status=${encodeURIComponent(status)}`);
    if (department) params.push(`department=${encodeURIComponent(department)}`);
    const query = params.length ? `?${params.join('&')}` : '';
    return this.http.get<GrievanceSummary[]>(`${API_BASE}/grievances${query}`);
  }

  updateStatus(grievanceId: string, newStatus: string, note: string, changedBy: string): Observable<UpdateStatusResult> {
    return this.http.post<UpdateStatusResult>(`${API_BASE}/grievances/${grievanceId}/status`, {
      newStatus,
      note,
      changedBy
    });
  }

  reopen(grievanceId: string, reason: string, reopenedBy: string): Observable<ReopenResult> {
    return this.http.post<ReopenResult>(`${API_BASE}/grievances/${grievanceId}/reopen`, { reason, reopenedBy });
  }

  getTrends(department: string | null, days: number): Observable<TrendsResponse> {
    const params: string[] = [`days=${days}`];
    if (department) params.push(`department=${encodeURIComponent(department)}`);
    return this.http.get<TrendsResponse>(`${API_BASE}/grievances/trends?${params.join('&')}`);
  }

  /**
   * The backend's SSE endpoint is POST-based (needs a JSON body), so the browser's native
   * EventSource (GET-only) can't be used -- reads the streamed text/event-stream response body
   * directly via fetch + ReadableStream instead, parsing "event:"/"data:" frames by hand.
   */
  async streamChat(question: string, callbacks: ChatStreamCallbacks, signal?: AbortSignal): Promise<void> {
    try {
      const response = await fetch(`${API_BASE}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
        signal
      });

      if (!response.ok || !response.body) {
        callbacks.onError(`Chat request failed (${response.status})`);
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let boundary: number;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          this.dispatchSseFrame(frame, callbacks);
        }
      }
      callbacks.onDone();
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        callbacks.onError((err as Error).message ?? 'Chat stream failed');
      }
    }
  }

  private dispatchSseFrame(frame: string, callbacks: ChatStreamCallbacks): void {
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        // No .trim() here -- Spring writes "data:<content>" with no separator space, so a
        // leading space in content (e.g. "data: to") is part of the actual streamed token text,
        // not SSE framing. Trimming it was silently eating the space before every token.
        dataLines.push(line.slice(5));
      }
    }
    if (dataLines.length === 0) return;
    const data = dataLines.join('\n');

    if (event === 'token') {
      callbacks.onToken(data);
    } else if (event === 'sources') {
      try {
        callbacks.onSources(JSON.parse(data) as RetrievedSource[]);
      } catch {
        callbacks.onSources([]);
      }
    } else if (event === 'error') {
      callbacks.onError(data);
    }
  }
}
