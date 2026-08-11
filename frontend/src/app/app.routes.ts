import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/landing/landing').then((m) => m.Landing)
  },
  {
    path: 'citizen',
    loadComponent: () => import('./pages/citizen/citizen').then((m) => m.Citizen)
  },
  {
    path: 'employee',
    loadComponent: () => import('./pages/employee/employee').then((m) => m.Employee)
  },
  { path: '**', redirectTo: '' }
];
