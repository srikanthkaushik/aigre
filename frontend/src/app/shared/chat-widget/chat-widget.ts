import { Component, input, signal } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { RetrievedSource } from '../../core/models';
import { DepartmentNamePipe } from '../../core/department-name.pipe';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  sources?: RetrievedSource[];
  streaming?: boolean;
}

const DEFAULT_EXAMPLE_QUESTIONS = [
  'How long does DOT have to repair a reported pothole?',
  'Who do I contact if a street light has been out for two weeks?',
  "What are my rights if my landlord hasn't fixed a heating outage?"
];

/**
 * Extracted from the old citizen.ts "Ask a Question" tab, verbatim behavior -- now reusable by
 * both the site-wide floating launcher (FloatingChat, unscoped) and the embeddable per-department
 * widget (EmbedChat, /embed/chat). department() being set restricts answers to that department's
 * own RAG corpus (ChatController/RetrievalService); the generic example questions below reference
 * DOT/landlord policy and wouldn't make sense for e.g. a DMV-scoped embed, so they're skipped in
 * that case in favor of a generic empty state.
 */
@Component({
  selector: 'app-chat-widget',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, DepartmentNamePipe],
  templateUrl: './chat-widget.html',
  styleUrl: './chat-widget.scss'
})
export class ChatWidget {
  readonly department = input<string | null>(null);

  question = '';
  readonly chatting = signal(false);
  readonly messages = signal<ChatMessage[]>([]);
  readonly exampleQuestions = DEFAULT_EXAMPLE_QUESTIONS;

  constructor(private readonly api: ApiService) {}

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

    this.api.streamChat(
      q,
      {
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
      },
      this.department()
    );
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

  citationLabel(source: RetrievedSource): string {
    const name = (source.metadata['source'] as string | undefined) ?? 'unknown source';
    const withoutExtension = name.replace(/\.[^./\\]+$/, '');
    return `CITED FROM: ${withoutExtension}`;
  }
}
