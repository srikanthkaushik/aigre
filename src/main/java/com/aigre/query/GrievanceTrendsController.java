package com.aigre.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The employee Trends dashboard's single data call: volume, sentiment, categories, priorities and
 * SLA compliance over the last {@code days}. Requires a login. The department filter is taken
 * from the request as-is (absent = all departments), not checked against the caller's own
 * department -- acceptable for aggregate counts, unlike the per-grievance endpoints DepartmentAccess
 * guards.
 */
@RestController
public class GrievanceTrendsController {

    private final GrievanceTrendsService service;

    public GrievanceTrendsController(GrievanceTrendsService service) {
        this.service = service;
    }

    @GetMapping("/grievances/trends")
    public Mono<TrendsResponse> trends(
            @RequestParam(required = false) String department, @RequestParam(defaultValue = "30") int days) {
        return Mono.fromCallable(() -> service.trends(department, days))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
