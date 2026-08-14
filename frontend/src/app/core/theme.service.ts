import { Injectable, computed, effect, signal } from '@angular/core';

export type ThemePreference = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'aigre.theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _preference = signal<ThemePreference>(loadStoredPreference());
  readonly preference = this._preference.asReadonly();

  // Tracks the OS setting live so resolvedMode updates if the user is on 'system'
  // and flips their OS theme while the tab is open.
  private readonly _systemPrefersDark = signal(matchMediaDark()?.matches ?? false);

  readonly resolvedMode = computed<'light' | 'dark'>(() =>
    this._preference() === 'system'
      ? this._systemPrefersDark()
        ? 'dark'
        : 'light'
      : (this._preference() as 'light' | 'dark')
  );

  constructor() {
    matchMediaDark()?.addEventListener('change', (e) => this._systemPrefersDark.set(e.matches));

    // Synchronizing with the DOM (a non-Angular system) is exactly what effect() is for.
    effect(() => {
      document.documentElement.style.setProperty('color-scheme', this.resolvedMode());
    });
  }

  setPreference(pref: ThemePreference): void {
    this._preference.set(pref);
    localStorage.setItem(STORAGE_KEY, pref);
  }
}

function matchMediaDark(): MediaQueryList | null {
  return typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null;
}

function loadStoredPreference(): ThemePreference {
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === 'light' || raw === 'dark' ? raw : 'system';
}
