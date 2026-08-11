package com.aigre.intake;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * This is a WebFlux app, but intake uses blocking JDBC (per the canonical
 * stack's starter-jdbc, not r2dbc) — offloaded to boundedElastic so the
 * blocking call doesn't tie up a Netty event-loop thread.
 */
@RestController
@RequestMapping("/grievances")
public class GrievanceController {

    private final GrievanceIntakeService service;

    public GrievanceController(GrievanceIntakeService service) {
        this.service = service;
    }

    @PostMapping
    public Mono<GrievanceIntakeResponse> submit(@Valid @RequestBody GrievanceIntakeRequest request) {
        return Mono.fromCallable(() -> service.submit(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
