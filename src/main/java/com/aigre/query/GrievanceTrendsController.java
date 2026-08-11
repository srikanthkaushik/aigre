package com.aigre.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
