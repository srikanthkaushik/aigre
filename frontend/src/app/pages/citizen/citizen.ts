import { Component, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';
import { GrievanceStatusResult, GrievanceWorkflowResponse } from '../../core/models';
import { DepartmentNamePipe } from '../../core/department-name.pipe';

@Component({
  selector: 'app-citizen',
  imports: [
    FormsModule,
    DatePipe,
    MatTabsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    DepartmentNamePipe
  ],
  templateUrl: './citizen.html',
  styleUrl: './citizen.scss'
})
export class Citizen {
  readonly selectedTabIndex = signal(0);

  // -- submit tab --
  rawText = '';
  citizenName = '';
  citizenEmail = '';
  readonly submitting = signal(false);
  readonly submitResult = signal<GrievanceWorkflowResponse | null>(null);
  readonly submitError = signal<string | null>(null);

  // -- inline clarification, offered when a submission comes back pendingReview --
  // Capped at MAX_CLARIFICATION_ATTEMPTS, not one-shot: classification confidence has
  // documented run-to-run sampling variance (see PROJECT.md), so a citizen who hits an unlucky
  // round has a real chance a retry just works -- confirmed live: the exact same clarification
  // text failed once, then succeeded immediately on retry with zero code changes in between.
  static readonly MAX_CLARIFICATION_ATTEMPTS = 2;
  readonly maxClarificationAttempts = Citizen.MAX_CLARIFICATION_ATTEMPTS;
  clarificationText = '';
  readonly clarifying = signal(false);
  readonly clarificationAttempts = signal(0);
  readonly clarificationError = signal<string | null>(null);

  // -- status tab --
  lookupId = '';
  readonly statusLoading = signal(false);
  readonly statusResult = signal<GrievanceStatusResult | null>(null);
  readonly statusError = signal<string | null>(null);

  // -- reopen, offered only when a looked-up grievance is CLOSED (plan.md scenario 7) --
  reopenReason = '';
  readonly reopening = signal(false);
  readonly reopenError = signal<string | null>(null);
  readonly reopenSuccess = signal(false);

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.rawText.trim()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    this.submitResult.set(null);
    this.clarificationText = '';
    this.clarifying.set(false);
    this.clarificationAttempts.set(0);
    this.clarificationError.set(null);

    this.api
      .submitGrievance({
        rawText: this.rawText,
        citizenName: this.citizenName || null,
        citizenEmail: this.citizenEmail || null
      })
      .subscribe({
        next: (result) => {
          this.submitResult.set(result);
          this.submitting.set(false);
          this.rawText = '';
        },
        error: (err) => {
          this.submitError.set(err?.error?.message ?? 'Submission failed. Please try again.');
          this.submitting.set(false);
        }
      });
  }

  /**
   * Offered inline right after a submission comes back pendingReview, up to
   * MAX_CLARIFICATION_ATTEMPTS times -- bounded, not open-ended, but not one-shot either (see
   * the field comment above for why a retry is worth offering).
   */
  submitClarification(): void {
    const id = this.submitResult()?.grievanceId;
    if (!id || !this.clarificationText.trim()) return;

    this.clarifying.set(true);
    this.clarificationError.set(null);
    const submittedText = this.clarificationText;
    this.clarificationText = '';

    this.api.clarify(id, submittedText).subscribe({
      next: (result) => {
        this.submitResult.set(result);
        this.clarifying.set(false);
        this.clarificationAttempts.update((n) => n + 1);
      },
      error: (err) => {
        this.clarificationError.set(err?.error?.message ?? 'Could not submit your additional detail. Please try again.');
        this.clarifying.set(false);
        this.clarificationText = submittedText;
      }
    });
  }

  get clarificationAttemptsRemaining(): number {
    return this.maxClarificationAttempts - this.clarificationAttempts();
  }

  lookupStatus(): void {
    if (!this.lookupId.trim()) return;
    this.statusLoading.set(true);
    this.statusError.set(null);
    this.statusResult.set(null);
    this.reopenReason = '';
    this.reopenError.set(null);
    this.reopenSuccess.set(false);

    this.api.getStatus(this.lookupId.trim()).subscribe({
      next: (result) => {
        this.statusResult.set(result);
        this.statusLoading.set(false);
      },
      error: () => {
        this.statusError.set('No grievance found with that ID. Double-check the ID and try again.');
        this.statusLoading.set(false);
      }
    });
  }

  /**
   * Refetches status directly (not via lookupStatus(), which resets reopenSuccess -- that would
   * immediately hide the confirmation this method is trying to show).
   */
  reopenGrievance(): void {
    const id = this.statusResult()?.id;
    if (!id || !this.reopenReason.trim()) return;
    this.reopening.set(true);
    this.reopenError.set(null);

    this.api.reopen(id, this.reopenReason.trim(), 'citizen-portal').subscribe({
      next: () => {
        this.reopening.set(false);
        this.reopenSuccess.set(true);
        this.reopenReason = '';
        this.api.getStatus(id).subscribe((result) => this.statusResult.set(result));
      },
      error: (err) => {
        this.reopenError.set(err?.error?.message ?? 'Could not reopen this complaint. Please try again.');
        this.reopening.set(false);
      }
    });
  }

  useSubmittedId(): void {
    const id = this.submitResult()?.grievanceId;
    if (!id) return;
    this.lookupId = id;
    this.selectedTabIndex.set(1);
    this.lookupStatus();
  }
}
