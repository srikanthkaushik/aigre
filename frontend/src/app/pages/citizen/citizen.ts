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
import { GrievanceStatusResult, GrievanceWorkflowResponse, RetrievedSource } from '../../core/models';
import { DepartmentNamePipe } from '../../core/department-name.pipe';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  sources?: RetrievedSource[];
  streaming?: boolean;
}

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

  // -- inline clarification, offered once when a submission comes back pendingReview --
  clarificationText = '';
  readonly clarifying = signal(false);
  readonly clarificationAttempted = signal(false);
  readonly clarificationError = signal<string | null>(null);

  // -- status tab --
  lookupId = '';
  readonly statusLoading = signal(false);
  readonly statusResult = signal<GrievanceStatusResult | null>(null);
  readonly statusError = signal<string | null>(null);

  // -- chat tab --
  question = '';
  readonly chatting = signal(false);
  readonly messages = signal<ChatMessage[]>([]);
  readonly exampleQuestions = [
    'How long does DOT have to repair a reported pothole?',
    'Who do I contact if a street light has been out for two weeks?',
    "What are my rights if my landlord hasn't fixed a heating outage?"
  ];

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.rawText.trim()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    this.submitResult.set(null);
    this.clarificationText = '';
    this.clarifying.set(false);
    this.clarificationAttempted.set(false);
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
   * Offered once, inline, right after a submission comes back pendingReview -- the citizen gets
   * one chance to add detail that might resolve the ambiguity themselves before it falls back to
   * a supervisor. Whatever happens (resolves or still needs a human), the form doesn't reappear
   * for this submission -- avoids an open-ended back-and-forth loop.
   */
  submitClarification(): void {
    const id = this.submitResult()?.grievanceId;
    if (!id || !this.clarificationText.trim()) return;

    this.clarifying.set(true);
    this.clarificationError.set(null);

    this.api.clarify(id, this.clarificationText).subscribe({
      next: (result) => {
        this.submitResult.set(result);
        this.clarifying.set(false);
        this.clarificationAttempted.set(true);
      },
      error: (err) => {
        this.clarificationError.set(err?.error?.message ?? 'Could not submit your additional detail. Please try again.');
        this.clarifying.set(false);
      }
    });
  }

  lookupStatus(): void {
    if (!this.lookupId.trim()) return;
    this.statusLoading.set(true);
    this.statusError.set(null);
    this.statusResult.set(null);

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

  useSubmittedId(): void {
    const id = this.submitResult()?.grievanceId;
    if (!id) return;
    this.lookupId = id;
    this.selectedTabIndex.set(1);
    this.lookupStatus();
  }

  askExampleQuestion(q: string): void {
    this.question = q;
    this.askQuestion();
  }

  askQuestion(): void {
    const q = this.question.trim();
    if (!q || this.chatting()) return;
    this.question = '';

    this.messages.update((m) => [...m, { role: 'user', text: q }]);
    this.messages.update((m) => [...m, { role: 'assistant', text: '', streaming: true }]);
    this.chatting.set(true);

    this.api.streamChat(q, {
      onToken: (token) => this.appendToLastAssistantMessage(token),
      onSources: (sources) => this.setLastAssistantSources(sources),
      onError: (message) => this.appendToLastAssistantMessage(`\n\n[error: ${message}]`),
      onDone: () => {
        this.chatting.set(false);
        this.messages.update((m) => {
          const copy = [...m];
          const last = copy[copy.length - 1];
          if (last) copy[copy.length - 1] = { ...last, streaming: false };
          return copy;
        });
      }
    });
  }

  private appendToLastAssistantMessage(token: string): void {
    this.messages.update((m) => {
      const copy = [...m];
      const last = copy[copy.length - 1];
      if (last && last.role === 'assistant') {
        copy[copy.length - 1] = { ...last, text: last.text + token };
      }
      return copy;
    });
  }

  private setLastAssistantSources(sources: RetrievedSource[]): void {
    this.messages.update((m) => {
      const copy = [...m];
      const last = copy[copy.length - 1];
      if (last && last.role === 'assistant') {
        copy[copy.length - 1] = { ...last, sources: this.dedupeBySource(sources) };
      }
      return copy;
    });
  }

  /**
   * The backend returns the top N *chunks*, not top N *documents* -- a single well-matched
   * document commonly fills several of those slots (confirmed live: "immunization-clinic-
   * access-faq.txt" appeared 3 times for one question), which read as repeated/broken citations
   * in the UI. Collapses to one citation card per source file, keeping the highest-ranked
   * occurrence (backend already returns chunks sorted by rerankScore descending).
   */
  private dedupeBySource(sources: RetrievedSource[]): RetrievedSource[] {
    const seen = new Set<string>();
    const deduped: RetrievedSource[] = [];
    for (const s of sources) {
      const key = (s.metadata['source'] as string | undefined) ?? s.text;
      if (seen.has(key)) continue;
      seen.add(key);
      deduped.push(s);
    }
    return deduped;
  }
}
