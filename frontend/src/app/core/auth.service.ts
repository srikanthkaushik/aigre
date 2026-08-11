import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export interface EmployeeSession {
  token: string;
  employeeId: string;
  name: string;
  departmentId: string;
  role: 'AGENT' | 'SUPERVISOR';
}

const API_BASE = 'http://localhost:8085';
// sessionStorage, not localStorage -- cleared when the tab closes rather than persisting
// indefinitely. Storing a JWT in web storage at all carries the usual XSS-exfiltration caveat;
// accepted here for a demo app with no third-party scripts.
const STORAGE_KEY = 'aigre.employee.session';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _session = signal<EmployeeSession | null>(loadStoredSession());
  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => this._session() !== null);
  readonly isSupervisor = computed(() => this._session()?.role === 'SUPERVISOR');

  constructor(private readonly http: HttpClient) {}

  login(username: string, password: string): Observable<EmployeeSession> {
    return this.http.post<EmployeeSession>(`${API_BASE}/auth/login`, { username, password }).pipe(
      tap((session) => {
        this._session.set(session);
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
      })
    );
  }

  logout(): void {
    this._session.set(null);
    sessionStorage.removeItem(STORAGE_KEY);
  }

  get token(): string | null {
    return this._session()?.token ?? null;
  }
}

function loadStoredSession(): EmployeeSession | null {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as EmployeeSession;
  } catch {
    return null;
  }
}
