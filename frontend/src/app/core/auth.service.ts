import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export interface EmployeeSession {
  token: string;
  employeeId: string;
  name: string;
  // ADMIN (cross-department oversight) has no department -- see schema.sql's comment on
  // department_employees.department_id.
  departmentId: string | null;
  role: 'AGENT' | 'SUPERVISOR' | 'ADMIN';
}

// Relative, not absolute -- see the matching note in api.service.ts. This file has its own
// separate constant rather than importing api.service.ts's, since login predates having a
// shared HTTP concern between the two and splitting it out wasn't worth it for one constant.
const API_BASE = '';
// sessionStorage, not localStorage -- cleared when the tab closes rather than persisting
// indefinitely. Storing a JWT in web storage at all carries the usual XSS-exfiltration caveat;
// accepted here for a demo app with no third-party scripts.
const STORAGE_KEY = 'aigre.employee.session';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _session = signal<EmployeeSession | null>(loadStoredSession());
  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => this._session() !== null);
  // ADMIN can do everything a SUPERVISOR can (and across every department, not just its own) --
  // the actual enforcement is server-side (SecurityConfig.hasAnyRole("SUPERVISOR", "ADMIN"));
  // this just decides which action buttons the UI offers.
  readonly isSupervisor = computed(() => {
    const role = this._session()?.role;
    return role === 'SUPERVISOR' || role === 'ADMIN';
  });
  readonly isAdmin = computed(() => this._session()?.role === 'ADMIN');

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
