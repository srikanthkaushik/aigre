import { Component, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ChatWidget } from '../chat-widget/chat-widget';

/**
 * Site-wide floating "Chat" launcher (bottom-right), replacing the old citizen-portal-only "Ask
 * a Question" tab -- mounted once in app.html so it's available from any page (App hides it on
 * /employee/** and /embed/** via its bareLayout logic). Unscoped: full RAG corpus, same behavior
 * the old tab gave citizens, just relocated.
 */
@Component({
  selector: 'app-floating-chat',
  imports: [MatButtonModule, MatIconModule, ChatWidget],
  templateUrl: './floating-chat.html',
  styleUrl: './floating-chat.scss'
})
export class FloatingChat {
  readonly open = signal(false);

  toggle(): void {
    this.open.update((o) => !o);
  }
}
