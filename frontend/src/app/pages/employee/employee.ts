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

  readonly pendingDataSource = new MatTableDataSource<GrievanceSummary>([]);
  readonly departmentDataSource = new MatTableDataSource<GrievanceSummary>([]);

  // ADMIN's queue spans every department, so a department column is the only way to tell rows
  // apart -- AGENT/SUPERVISOR don't need it, their queue is a single department by definition.
  get pendingColumns(): string[] {
    return this.auth.isAdmin()
      ? ['submittedAt', 'department', 'status', 'actions']
      : ['submittedAt', 'status', 'actions'];
  }

  get departmentColumns(): string[] {
    return this.auth.isAdmin()
      ? ['submittedAt', 'department', 'status', 'category', 'priority', 'slaDueAt', 'actions']
      : ['submittedAt', 'status', 'category', 'priority', 'slaDueAt', 'actions'];
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
  get department(): string {
    return this.auth.session()?.departmentId ?? '';
  }

  get departmentLabel(): string {
    return this.auth.isAdmin() ? 'All Departments' : this.department;
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }

  refresh(): void {
    this.loading.set(true);
    // Both tabs are now department-scoped server-side (SecurityConfig/GrievanceQueryController
    // derive the department from the logged-in employee's token, not a client-supplied value) --
    // they differ only by status filter, not by department anymore.
    this.api.listGrievances('NEW').subscribe({
      next: (items) => (this.pendingDataSource.data = items),
      error: () => (this.pendingDataSource.data = [])
    });
    this.api.listGrievances().subscribe({
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
