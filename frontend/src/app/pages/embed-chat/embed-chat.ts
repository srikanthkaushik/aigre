import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { ChatWidget } from '../../shared/chat-widget/chat-widget';
import { DepartmentService } from '../../core/department.service';

/**
 * Full-bleed chat page (no toolbar/footer -- see app.ts's bareLayout logic) meant to be loaded
 * inside an iframe by an external site's embed script (frontend/public/embed.js), one per
 * department: /embed/chat?department=DMV. The department query param is validated against the
 * live /departments list (DepartmentService) purely for a friendly message on a typo'd code --
 * the actual security boundary (which *sites* may embed this at all) is enforced server-side by
 * EmbedChatController's per-department Content-Security-Policy: frame-ancestors header, not here.
 */
@Component({
  selector: 'app-embed-chat',
  imports: [ChatWidget],
  templateUrl: './embed-chat.html',
  styleUrl: './embed-chat.scss'
})
export class EmbedChat {
  private readonly route = inject(ActivatedRoute);
  private readonly departmentService = inject(DepartmentService);

  readonly department = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('department'))), {
    initialValue: this.route.snapshot.queryParamMap.get('department')
  });

  readonly status = computed<'loading' | 'valid' | 'invalid'>(() => {
    const dept = this.department();
    if (!dept) return 'invalid';
    if (this.departmentService.departments().length === 0) return 'loading';
    return this.departmentService.departmentIds().includes(dept) ? 'valid' : 'invalid';
  });
}
