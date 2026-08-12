import { AfterViewInit, Component, OnInit, ViewChild, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DEPARTMENTS, GrievanceSummary } from '../../core/models';
import { DepartmentNamePipe } from '../../core/department-name.pipe';
import { GrievanceDetailDialog } from './grievance-detail-dialog/grievance-detail-dialog';
import { Trends } from './trends/trends';

const PAGE_SIZE = 10;

@Component({
  selector: 'app-employee',
  imports: [
    DatePipe,
    MatTabsModule,
    MatButtonModule,
    MatTableModule,
    MatPaginatorModule,
    MatChipsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatSelectModule,
    DepartmentNamePipe,
    Trends
  ],
  templateUrl: './employee.html',
  styleUrl: './employee.scss'
})
export class Employee implements OnInit, AfterViewInit {
  readonly pageSize = PAGE_SIZE;

  readonly loading = signal(false);
  readonly breachedCount = signal(0);

  // ADMIN-only: '' means "all departments" (its default cross-department view); a specific code
  // narrows both tables to that one department, same as a non-admin employee already sees.
  readonly departmentFilter = signal('');
  readonly departmentOptions = DEPARTMENTS;

  readonly pendingDataSource = new MatTableDataSource<GrievanceSummary>([]);
  readonly departmentDataSource = new MatTableDataSource<GrievanceSummary>([]);

  // ADMIN's queue can span every department, so the department column is only needed while it's
  // not filtered down to one -- AGENT/SUPERVISOR never need it, their queue is single-department
  // by definition.
  get pendingColumns(): string[] {
    return this.auth.isAdmin() && !this.departmentFilter()
      ? ['submittedAt', 'department', 'channel', 'status', 'actions']
      : ['submittedAt', 'channel', 'status', 'actions'];
  }

  get departmentColumns(): string[] {
    return this.auth.isAdmin() && !this.departmentFilter()
      ? ['submittedAt', 'department', 'channel', 'status', 'category', 'priority', 'slaDueAt', 'actions']
      : ['submittedAt', 'channel', 'status', 'category', 'priority', 'slaDueAt', 'actions'];
  }

  @ViewChild('pendingPaginator') private pendingPaginator!: MatPaginator;
  @ViewChild('departmentPaginator') private departmentPaginator!: MatPaginator;

  constructor(
    private readonly api: ApiService,
    private readonly dialog: MatDialog,
    readonly auth: AuthService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngAfterViewInit(): void {
    this.pendingDataSource.paginator = this.pendingPaginator;
    this.departmentDataSource.paginator = this.departmentPaginator;
  }

  // Empty string, not null -- passed straight through to ApiService.getTrends()/the Trends
  // component, whose "all departments" handling already treats a blank department as no filter.
  // For ADMIN (no department of its own) this tracks the dashboard's department filter instead,
  // so the Trends tab's own "department" toggle stays meaningful rather than always blank.
  get department(): string {
    return this.auth.isAdmin() ? this.departmentFilter() : (this.auth.session()?.departmentId ?? '');
  }

  get departmentLabel(): string {
    if (!this.auth.isAdmin()) return this.department;
    return this.departmentFilter() || 'All Departments';
  }

  onDepartmentFilterChange(department: string): void {
    this.departmentFilter.set(department);
    this.refresh();
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }

  refresh(): void {
    this.loading.set(true);
    // Both tabs are department-scoped server-side (GrievanceQueryController derives the
    // department from the logged-in employee's token for AGENT/SUPERVISOR). ADMIN has no token
    // department, so its optional filter is sent explicitly -- the backend only honors it for
    // that role (see GrievanceQueryController.list's javadoc).
    const department = this.auth.isAdmin() ? this.departmentFilter() : undefined;
    this.api.listGrievances('NEW', department).subscribe({
      next: (items) => (this.pendingDataSource.data = items),
      error: () => (this.pendingDataSource.data = [])
    });
    this.api.listGrievances(undefined, department).subscribe({
      next: (items) => {
        this.departmentDataSource.data = items;
        this.breachedCount.set(items.filter((i) => i.breached).length);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  openDetail(id: string): void {
    const session = this.auth.session();
    const ref = this.dialog.open(GrievanceDetailDialog, {
      data: {
        grievanceId: id,
        departments: DEPARTMENTS,
        priorities: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'],
        defaultReviewedBy: session?.name ?? session?.departmentId ?? 'employee'
      }
    });

    ref.afterClosed().subscribe((didReview) => {
      if (didReview) this.refresh();
    });
  }
}
