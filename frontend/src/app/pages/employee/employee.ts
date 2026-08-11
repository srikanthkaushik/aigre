import { AfterViewInit, Component, OnInit, ViewChild, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ApiService } from '../../core/api.service';
import { DEPARTMENTS, GrievanceSummary } from '../../core/models';
import { DepartmentNamePipe } from '../../core/department-name.pipe';
import { GrievanceDetailDialog } from './grievance-detail-dialog/grievance-detail-dialog';
import { Trends } from './trends/trends';

const DEPARTMENT_STORAGE_KEY = 'aigre.employee.department';
const PAGE_SIZE = 10;

@Component({
  selector: 'app-employee',
  imports: [
    FormsModule,
    DatePipe,
    MatTabsModule,
    MatFormFieldModule,
    MatSelectModule,
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
  readonly departments = DEPARTMENTS;
  readonly pageSize = PAGE_SIZE;

  readonly department = signal<string>(localStorage.getItem(DEPARTMENT_STORAGE_KEY) ?? DEPARTMENTS[0]);
  readonly loading = signal(false);
  readonly breachedCount = signal(0);

  readonly pendingDataSource = new MatTableDataSource<GrievanceSummary>([]);
  readonly departmentDataSource = new MatTableDataSource<GrievanceSummary>([]);

  readonly pendingColumns = ['submittedAt', 'status', 'actions'];
  readonly departmentColumns = ['submittedAt', 'status', 'category', 'priority', 'slaDueAt', 'actions'];

  @ViewChild('pendingPaginator') private pendingPaginator!: MatPaginator;
  @ViewChild('departmentPaginator') private departmentPaginator!: MatPaginator;

  constructor(private readonly api: ApiService, private readonly dialog: MatDialog) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngAfterViewInit(): void {
    this.pendingDataSource.paginator = this.pendingPaginator;
    this.departmentDataSource.paginator = this.departmentPaginator;
  }

  onDepartmentChange(dept: string): void {
    this.department.set(dept);
    localStorage.setItem(DEPARTMENT_STORAGE_KEY, dept);
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.listGrievances(null, 'NEW').subscribe({
      next: (items) => (this.pendingDataSource.data = items),
      error: () => (this.pendingDataSource.data = [])
    });
    this.api.listGrievances(this.department(), null).subscribe({
      next: (items) => {
        this.departmentDataSource.data = items;
        this.breachedCount.set(items.filter((i) => i.breached).length);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  openDetail(id: string): void {
    const ref = this.dialog.open(GrievanceDetailDialog, {
      data: {
        grievanceId: id,
        departments: DEPARTMENTS,
        priorities: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'],
        defaultReviewedBy: `${this.department()}-supervisor`
      }
    });

    ref.afterClosed().subscribe((didReview) => {
      if (didReview) this.refresh();
    });
  }
}
