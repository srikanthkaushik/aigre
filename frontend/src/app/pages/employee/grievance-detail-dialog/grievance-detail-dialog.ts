import { Component, Inject, OnInit, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../../core/api.service';
import { AuthService } from '../../../core/auth.service';
import { GrievanceWorkflowResponse } from '../../../core/models';
import { DepartmentNamePipe } from '../../../core/department-name.pipe';

export interface GrievanceDetailDialogData {
  grievanceId: string;
  departments: readonly string[];
  priorities: readonly string[];
  defaultReviewedBy: string;
}

/** Result: true if a review was submitted (caller should refresh its lists), false/undefined otherwise. */
@Component({
  selector: 'app-grievance-detail-dialog',
  imports: [
    FormsModule,
    DatePipe,
    DecimalPipe,
    MatDialogModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatButtonModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    DepartmentNamePipe
  ],
  templateUrl: './grievance-detail-dialog.html',
  styleUrl: './grievance-detail-dialog.scss'
})
export class GrievanceDetailDialog implements OnInit {
  readonly detail = signal<GrievanceWorkflowResponse | null>(null);
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  reviewDepartment = '';
  reviewCategory = '';
  reviewPriority = '';
  reviewNote = '';
  reviewedBy: string;

  readonly markingStatus = signal(false);
  resolveNote = '';

  constructor(
    private readonly api: ApiService,
    private readonly dialogRef: MatDialogRef<GrievanceDetailDialog, boolean>,
    readonly auth: AuthService,
    @Inject(MAT_DIALOG_DATA) public data: GrievanceDetailDialogData
  ) {
    this.reviewedBy = data.defaultReviewedBy;
  }

  ngOnInit(): void {
    this.api.getWorkflowStatus(this.data.grievanceId).subscribe({
      next: (detail) => this.detail.set(detail),
      error: () => this.error.set('Could not load this grievance.')
    });
  }

  submitReview(): void {
    if (!this.reviewNote.trim() || !this.reviewedBy.trim()) return;
    this.submitting.set(true);
    this.error.set(null);

    this.api
      .resumeReview(this.data.grievanceId, {
        department: this.reviewDepartment || null,
        category: this.reviewCategory || null,
        priority: this.reviewPriority || null,
        note: this.reviewNote,
        reviewedBy: this.reviewedBy
      })
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.dialogRef.close(true);
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Failed to submit review.');
          this.submitting.set(false);
        }
      });
  }

  /**
   * Employee-facing lifecycle action -- "Start Work"/"Mark In Progress"/"Mark Resolved"/
   * "Mark Closed", all thin wrappers around the same update_grievance_status MCP tool over
   * HTTP (POST /grievances/{id}/status already accepts any of these; nothing new needed
   * backend-side). ROUTED/IN_PROGRESS previously had no dashboard action at all -- a
   * committed case could only ever jump straight to Resolved/Closed.
   */
  markStatus(newStatus: 'ROUTED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'): void {
    if (!this.resolveNote.trim() || !this.reviewedBy.trim()) return;
    this.markingStatus.set(true);
    this.error.set(null);

    this.api.updateStatus(this.data.grievanceId, newStatus, this.resolveNote, this.reviewedBy).subscribe({
      next: () => {
        this.markingStatus.set(false);
        this.dialogRef.close(true);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Failed to update status.');
        this.markingStatus.set(false);
      }
    });
  }

  close(): void {
    this.dialogRef.close(false);
  }
}
