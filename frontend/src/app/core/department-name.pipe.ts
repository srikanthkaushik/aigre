import { Pipe, PipeTransform, inject } from '@angular/core';
import { DepartmentService } from './department.service';

// pure: false -- reads a live signal from DepartmentService (populated asynchronously after the
// initial GET /departments resolves), so this must re-run once that signal updates, not just
// when `code` itself changes.
@Pipe({ name: 'departmentName', pure: false })
export class DepartmentNamePipe implements PipeTransform {
  private readonly departmentService = inject(DepartmentService);

  transform(code: string | null | undefined): string {
    if (!code) return '—';
    return this.departmentService.departmentNames()[code] ?? code;
  }
}
