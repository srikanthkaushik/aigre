import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ThemeService } from './core/theme.service';
import { FloatingChat } from './shared/floating-chat/floating-chat';

const THEME_ICONS = { light: 'light_mode', dark: 'dark_mode', system: 'brightness_auto' } as const;

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    FloatingChat
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  readonly theme = inject(ThemeService);
  readonly themeIcon = computed(() => THEME_ICONS[this.theme.preference()]);

  private readonly router = inject(Router);

  /**
   * True on routes (e.g. /embed/chat) whose route data sets bareLayout: true -- those render
   * full-bleed for iframe embedding, with no toolbar/footer/floating-chat-launcher of their own.
   * Walks to the deepest activated route since route data lives on the leaf, not the router's
   * top-level snapshot.
   */
  private readonly navigationEnd = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => e as NavigationEnd)
    ),
    { initialValue: null }
  );

  private readonly bareLayout = computed(() => {
    this.navigationEnd(); // depend on navigation so this re-derives on every route change
    let route = this.router.routerState.snapshot.root;
    while (route.firstChild) route = route.firstChild;
    return !!route.data['bareLayout'];
  });

  private readonly currentUrl = computed(() => this.navigationEnd()?.urlAfterRedirects ?? this.router.url);

  readonly showAppShell = computed(() => !this.bareLayout());

  readonly showFloatingChat = computed(() => !this.bareLayout() && !this.currentUrl().startsWith('/employee'));
}
