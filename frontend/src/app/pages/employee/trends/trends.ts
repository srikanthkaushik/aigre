import { Component, computed, effect, input, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { BaseChartDirective, provideCharts, withDefaultRegisterables } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import { ApiService } from '../../../core/api.service';
import { DepartmentNamePipe } from '../../../core/department-name.pipe';
import { TrendsResponse } from '../../../core/models';

type Scope = 'department' | 'all';
type WindowDays = 7 | 30 | 90;

// Same seed/tonal colors as the app's Material theme (theme-colors.scss, generated from
// #1B3A57/#C17F2C) -- Chart.js can't consume the --mat-sys-* CSS custom properties directly, so
// these are hardcoded to match rather than left to Chart.js's default palette.
const PRIMARY = '#446180'; // primary tone 40
const PRIMARY_LIGHT = 'rgba(68, 97, 128, 0.15)';
const TERTIARY = '#885200'; // tertiary tone 40
const TERTIARY_LIGHT = 'rgba(136, 82, 0, 0.15)';
const PRIORITY_COLORS: Record<string, string> = {
  CRITICAL: '#ba1a1a',
  HIGH: '#c5822f',
  MEDIUM: '#446180',
  LOW: '#7693b5'
};

@Component({
  selector: 'app-trends',
  imports: [MatButtonToggleModule, MatIconModule, MatProgressSpinnerModule, BaseChartDirective, DepartmentNamePipe],
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

  readonly volumeChartData = computed<ChartConfiguration<'line'>['data']>(() => {
    const t = this.trends();
    return {
      labels: t?.volumeByDay.map((d) => d.date) ?? [],
      datasets: [
        {
          data: t?.volumeByDay.map((d) => d.count) ?? [],
          label: 'Complaints',
          borderColor: PRIMARY,
          backgroundColor: PRIMARY_LIGHT,
          fill: true,
          tension: 0.3
        }
      ]
    };
  });

  readonly categoryChartData = computed<ChartConfiguration<'bar'>['data']>(() => {
    const t = this.trends();
    return {
      labels: t?.byCategory.map((c) => c.category) ?? [],
      datasets: [{ data: t?.byCategory.map((c) => c.count) ?? [], label: 'Complaints', backgroundColor: PRIMARY }]
    };
  });

  readonly priorityChartData = computed<ChartConfiguration<'bar'>['data']>(() => {
    const t = this.trends();
    const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
    const byPriority = new Map((t?.byPriority ?? []).map((p) => [p.priority, p.count]));
    const present = order.filter((p) => byPriority.has(p));
    return {
      labels: present,
      datasets: [
        {
          data: present.map((p) => byPriority.get(p) ?? 0),
          backgroundColor: present.map((p) => PRIORITY_COLORS[p])
        }
      ]
    };
  });

  readonly sentimentChartData = computed<ChartConfiguration<'line'>['data']>(() => {
    const t = this.trends();
    return {
      labels: t?.sentimentByDay.map((d) => d.date) ?? [],
      datasets: [
        {
          data: t?.sentimentByDay.map((d) => Number(d.avgSentiment.toFixed(2))) ?? [],
          label: 'Avg. sentiment',
          borderColor: TERTIARY,
          backgroundColor: TERTIARY_LIGHT,
          fill: true,
          tension: 0.3
        }
      ]
    };
  });

  readonly barOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
  };

  readonly lineOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } }
  };

  readonly sentimentOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { y: { min: -1, max: 1 } }
  };

  constructor(private readonly api: ApiService) {
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
}
