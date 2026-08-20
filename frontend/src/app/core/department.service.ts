import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DepartmentSummary } from './models';

// Relative, not absolute -- same reasoning as ApiService.API_BASE.
const API_BASE = '';

@Injectable({ providedIn: 'root' })
export class DepartmentService {
  private readonly _departments = signal<DepartmentSummary[]>([]);
  readonly departments = this._departments.asReadonly();
  readonly departmentIds = computed(() => this._departments().map((d) => d.id));
  readonly departmentNames = computed<Record<string, string>>(() =>
    Object.fromEntries(this._departments().map((d) => [d.id, d.name]))
  );

  constructor(private readonly http: HttpClient) {
    this.http.get<DepartmentSummary[]>(`${API_BASE}/departments`).subscribe({
      next: (list) => this._departments.set(list),
      // A silent failure here previously meant the department dropdown showed nothing but
      // "All Departments" with no indication why -- confirmed live via a missing ng serve proxy
      // rule for /departments (fixed in proxy.conf.json), but any other transient failure would
      // have hit the exact same silent-empty-forever symptom without this.
      error: (err) => console.error('Failed to load departments -- department dropdowns will be empty.', err)
    });
  }
}
