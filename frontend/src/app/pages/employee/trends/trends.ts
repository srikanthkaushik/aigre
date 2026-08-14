import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { BaseChartDirective, provideCharts, withDefaultRegisterables } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import { ApiService } from '../../../core/api.service';
import { AuthService } from '../../../core/auth.service';
import { ThemeService } from '../../../core/theme.service';
import { DepartmentNamePipe } from '../../../core/department-name.pipe';
import { DailySentimentLevels, DEPARTMENTS, TrendsResponse } from '../../../core/models';
import { GrievanceDetailDialog } from '../grievance-detail-dialog/grievance-detail-dialog';

type Scope = 'department' | 'all';
type WindowDays = 7 | 30 | 90;

type SentimentKey = keyof Omit<DailySentimentLevels, 'date'>;

interface ChartPalette {
  primary: string;
  primaryLight: string;
  priority: Record<'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW', string>;
  sentiment: Record<SentimentKey, string>;
  tickText: string;
  gridline: string;
}

// Chart.js renders to canvas, not through the CSS cascade -- it can't consume --mat-sys-*
// custom properties or light-dark() at all, so both a light and a dark palette are hand-maintained
// here instead. The light values are the app's own Material theme seed/tonal colors
// (theme-colors.scss, generated from #1B3A57/#C17F2C). The dark values for primary/CRITICAL/HIGH/
// tickText aren't invented -- they're the actual compiled dark-mode values of --mat-sys-primary,
// --mat-sys-error, --mat-sys-tertiary, and --mat-sys-on-surface-variant respectively, i.e. exactly
// what the rest of the app already uses for the equivalent role in dark mode. LOW is dropped one
// tone (60->50) rather than reused as-is, since MEDIUM jumps to a much brighter tone-80 in dark
// mode and keeping LOW at its old tone would leave too little contrast between the two. The green
// sentiment values have no M3 seed to anchor to (this project's palette has no green) -- hand-picked,
// brightened versions of the light values, keeping the same hue identity.
const LIGHT_PALETTE: ChartPalette = {
  primary: '#446180', // primary tone 40
  primaryLight: 'rgba(68, 97, 128, 0.15)',
  priority: { CRITICAL: '#ba1a1a', HIGH: '#c5822f', MEDIUM: '#446180', LOW: '#7693b5' },
  // Diverging red -> amber -> grey -> green -> dark green, echoing the CRITICAL/HIGH tones above
  // at the low-confidence end so "No Confidence" reads as alarm-colored consistently across charts.
  sentiment: {
    noConfidence: '#ba1a1a',
    lowConfidence: '#c5822f',
    neutral: '#8c8c8c',
    moderateConfidence: '#6b9c5e',
    highConfidence: '#3f7d3f'
  },
  tickText: '#43474d',
  gridline: 'rgba(0, 0, 0, 0.08)'
};

const DARK_PALETTE: ChartPalette = {
  primary: '#acc9ed',
  primaryLight: 'rgba(172, 201, 237, 0.22)',
  priority: { CRITICAL: '#ffb4ab', HIGH: '#ffb868', MEDIUM: '#acc9ed', LOW: '#5d799a' },
  sentiment: {
    noConfidence: '#ffb4ab',
    lowConfidence: '#ffb868',
    neutral: '#a8abb3',
    moderateConfidence: '#8dbf82',
    highConfidence: '#6aab5f'
  },
  tickText: '#dfe2ea',
  gridline: 'rgba(255, 255, 255, 0.12)'
};

const SENTIMENT_LABELS: { key: SentimentKey; label: string }[] = [
  { key: 'noConfidence', label: 'No Confidence' },
  { key: 'lowConfidence', label: 'Low Confidence' },
  { key: 'neutral', label: 'Neutral' },
  { key: 'moderateConfidence', label: 'Moderate Confidence' },
  { key: 'highConfidence', label: 'High Confidence' }
];

@Component({
  selector: 'app-trends',
  imports: [
    DatePipe,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatButtonModule,
    MatChipsModule,
    MatDialogModule,
    BaseChartDirective,
    DepartmentNamePipe
  ],
  // Chart.js (~170kB) is only needed here -- scoping the provider to this lazily-loaded
  // component (not app.config.ts) keeps it out of every other route's bundle.
  providers: [provideCharts(withDefaultRegisterables())],
  templateUrl: './trends.html',
  styleUrl: './trends.scss'
})
export class Trends {
  readonly department = input.required<string>();

  readonly scope = signal<Scope>('department');
  readonly windowDays = signal<WindowDays>(30);
  readonly loading = signal(false);
  readonly trends = signal<TrendsResponse | null>(null);

  // Department column only earns its place when scope is 'all' -- a single-department view
  // already says which department it is via the toggle label above.
  get recurringIssuesColumns(): string[] {
    return this.scope() === 'all'
      ? ['category', 'department', 'firstReported', 'repeatCount', 'actions']
      : ['category', 'firstReported', 'repeatCount', 'actions'];
  }

  private readonly theme = inject(ThemeService);
  private readonly palette = computed(() => (this.theme.resolvedMode() === 'dark' ? DARK_PALETTE : LIGHT_PALETTE));

  readonly volumeChartData = computed<ChartConfiguration<'line'>['data']>(() => {
    const t = this.trends();
    const p = this.palette();
    return {
      labels: t?.volumeByDay.map((d) => d.date) ?? [],
      datasets: [
        {
          data: t?.volumeByDay.map((d) => d.count) ?? [],
          label: 'Complaints',
          borderColor: p.primary,
          backgroundColor: p.primaryLight,
          fill: true,
          tension: 0.3
        }
      ]
    };
  });

  readonly categoryChartData = computed<ChartConfiguration<'bar'>['data']>(() => {
    const t = this.trends();
    const p = this.palette();
    return {
      labels: t?.byCategory.map((c) => c.category) ?? [],
      datasets: [{ data: t?.byCategory.map((c) => c.count) ?? [], label: 'Complaints', backgroundColor: p.primary }]
    };
  });

  readonly priorityChartData = computed<ChartConfiguration<'bar'>['data']>(() => {
    const t = this.trends();
    const p = this.palette();
    const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;
    const byPriority = new Map((t?.byPriority ?? []).map((entry) => [entry.priority, entry.count]));
    const present = order.filter((priority) => byPriority.has(priority));
    return {
      labels: present,
      datasets: [
        {
          data: present.map((priority) => byPriority.get(priority) ?? 0),
          backgroundColor: present.map((priority) => p.priority[priority])
        }
      ]
    };
  });

  readonly sentimentChartData = computed<ChartConfiguration<'bar'>['data']>(() => {
    const t = this.trends();
    const p = this.palette();
    const days = t?.sentimentByDay ?? [];
    return {
      labels: days.map((d) => d.date),
      datasets: SENTIMENT_LABELS.map(({ key, label }) => ({
        data: days.map((d) => d[key]),
        label,
        backgroundColor: p.sentiment[key]
      }))
    };
  });

  readonly barOptions = computed<ChartConfiguration<'bar'>['options']>(() => {
    const p = this.palette();
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { color: p.tickText }, grid: { color: p.gridline } },
        y: { beginAtZero: true, ticks: { precision: 0, color: p.tickText }, grid: { color: p.gridline } }
      }
    };
  });

  readonly lineOptions = computed<ChartConfiguration<'line'>['options']>(() => {
    const p = this.palette();
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { color: p.tickText }, grid: { color: p.gridline } },
        y: { ticks: { color: p.tickText }, grid: { color: p.gridline } }
      }
    };
  });

  readonly sentimentOptions = computed<ChartConfiguration<'bar'>['options']>(() => {
    const p = this.palette();
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: true, position: 'bottom', labels: { color: p.tickText } } },
      scales: {
        x: { stacked: true, ticks: { color: p.tickText }, grid: { color: p.gridline } },
        y: { stacked: true, beginAtZero: true, ticks: { precision: 0, color: p.tickText }, grid: { color: p.gridline } }
      }
    };
  });

  constructor(
    private readonly api: ApiService,
    private readonly dialog: MatDialog,
    private readonly auth: AuthService
  ) {
    effect(() => {
      const dept = this.scope() === 'all' ? null : this.department();
      const days = this.windowDays();
      this.loading.set(true);
      this.api.getTrends(dept, days).subscribe({
        next: (response) => {
          this.trends.set(response);
          this.loading.set(false);
        },
        error: () => {
          this.trends.set(null);
          this.loading.set(false);
        }
      });
    });
  }

  openDetail(id: string): void {
    const session = this.auth.session();
    this.dialog.open(GrievanceDetailDialog, {
      data: {
        grievanceId: id,
        departments: DEPARTMENTS,
        priorities: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'],
        defaultReviewedBy: session?.name ?? session?.departmentId ?? 'employee'
      }
    });
  }
}
