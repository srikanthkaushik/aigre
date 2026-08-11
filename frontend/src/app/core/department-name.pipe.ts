import { Pipe, PipeTransform } from '@angular/core';
import { DEPARTMENT_NAMES } from './models';

@Pipe({ name: 'departmentName' })
export class DepartmentNamePipe implements PipeTransform {
  transform(code: string | null | undefined): string {
    if (!code) return '—';
    return DEPARTMENT_NAMES[code] ?? code;
  }
}
