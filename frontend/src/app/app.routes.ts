import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

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
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.Login)
  },
  {
    path: 'employee',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/employee/employee').then((m) => m.Employee)
  },
  { path: '**', redirectTo: '' }
];
