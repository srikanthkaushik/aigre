import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Mirrors the backend's DomainProperties (GET /app-config). */
export interface AppConfig {
  agencyName: string;
  tagline: string;
  footer: string;
  unitLabel: string;
  unitLabelPlural: string;
  chatSuggestions: string[];
  agencyWideEmbed: boolean;
}

// The city profile's values -- used until /app-config answers, and kept if it never does, so a
// failed call degrades to the original wording rather than blank labels.
const CITY_DEFAULTS: AppConfig = {
  agencyName: 'City Services',
  tagline:
    'Submit, track, and resolve city service complaints — classified, prioritized, and routed automatically, with a person always in the loop when it matters.',
  footer: 'A city services demonstration portal',
  unitLabel: 'Department',
  unitLabelPlural: 'Departments',
  chatSuggestions: [],
  agencyWideEmbed: false
};

/**
 * The backend's domain profile (city departments vs. DMV bureaus) as signals, loaded once at
 * startup by provideAppInitializer in app.config.ts. Routing units are "departments" in every API
 * and model; only the words shown to people come from here.
 */
@Injectable({ providedIn: 'root' })
export class AppConfigService {
  private readonly http = inject(HttpClient);
  private readonly config = signal<AppConfig>(CITY_DEFAULTS);

  readonly agencyName = computed(() => this.config().agencyName);
  readonly tagline = computed(() => this.config().tagline);
  readonly footer = computed(() => this.config().footer);
  readonly unitLabel = computed(() => this.config().unitLabel);
  readonly unitLabelPlural = computed(() => this.config().unitLabelPlural);
  readonly unitLabelLower = computed(() => this.config().unitLabel.toLowerCase());
  readonly unitLabelPluralLower = computed(() => this.config().unitLabelPlural.toLowerCase());
  readonly chatSuggestions = computed(() => this.config().chatSuggestions);
  readonly agencyWideEmbed = computed(() => this.config().agencyWideEmbed);

  async load(): Promise<void> {
    try {
      this.config.set({ ...CITY_DEFAULTS, ...(await firstValueFrom(this.http.get<AppConfig>('/app-config'))) });
    } catch {
      // Keep the defaults; the app still works, just with the city wording.
    }
  }
}
