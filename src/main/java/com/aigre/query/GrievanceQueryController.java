package com.aigre.query;

import com.aigre.auth.DepartmentAccess;
import com.aigre.auth.EmployeePrincipal;
import com.aigre.tools.GrievanceStatusResult;
import com.aigre.tools.ReopenResult;
import com.aigre.tools.UpdateStatusResult;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read-side REST surface for the frontend (milestone 5): a citizen status-lookup page and the
 * employee dashboard's grievance list, both usable regardless of which intake path (plain or
 * workflow) created the grievance.
 */
@RestController
@RequestMapping("/grievances")
public class GrievanceQueryController {

    private final GrievanceQueryService service;

    public GrievanceQueryController(GrievanceQueryService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Mono<GrievanceStatusResult> getStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> service.getStatus(id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Employee-facing (both the Pending Review and department-queue tables) -- department is
     * always derived from the authenticated employee, never a client-supplied value, which is
     * what actually makes this "strictly own department" rather than just "logged in": a
     * DOT employee's token can't be used to request ?department=DPW. The one exception is
     * ADMIN, which has no department of its own (see EmployeePrincipal.isAdmin()) -- for that
     * role only, an explicit ?department= is honored as a client-side filter narrowing its
     * already-cross-department view; omitted/blank still means "all departments."
     */
    @GetMapping
    public Flux<GrievanceSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String department,
            @AuthenticationPrincipal EmployeePrincipal principal) {
        String effectiveDepartment = principal.isAdmin() ? department : principal.departmentId();
        return Mono.fromCallable(() -> service.list(effectiveDepartment, status))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * Employee-facing lifecycle action (e.g. "Mark Resolved"/"Mark Closed") -- a thin HTTP
     * wrapper around the same update_grievance_status MCP tool an agent could call.
     * SUPERVISOR-only (SecurityConfig) and department-scoped, checked before the mutation.
     */
    @PostMapping("/{id}/status")
    public Mono<UpdateStatusResult> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateStatusRequest request,
            @AuthenticationPrincipal EmployeePrincipal principal) {
        return Mono.fromCallable(() -> {
            GrievanceStatusResult current = service.getStatus(id);
            String department =
                    current.departmentConfirmed() != null ? current.departmentConfirmed() : current.departmentPredicted();
            DepartmentAccess.requireOwnDepartment(principal, department);
            return service.updateStatus(id, request.newStatus(), request.note(), request.changedBy());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Citizen-facing reopen (plan.md scenario 7) -- only succeeds when the grievance is currently CLOSED. */
    @PostMapping("/{id}/reopen")
    public Mono<ReopenResult> reopen(@PathVariable String id, @Valid @RequestBody ReopenRequest request) {
        return Mono.fromCallable(() -> service.reopen(id, request.reason(), request.reopenedBy()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
