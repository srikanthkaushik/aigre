package com.aigre.query;

import com.aigre.tools.GrievanceStatusResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping
    public Flux<GrievanceSummary> list(
            @RequestParam(required = false) String department, @RequestParam(required = false) String status) {
        return Mono.fromCallable(() -> service.list(department, status))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }
}
