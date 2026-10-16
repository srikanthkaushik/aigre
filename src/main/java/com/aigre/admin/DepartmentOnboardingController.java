package com.aigre.admin;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * ADMIN-only entry point for onboarding a new department from a public URL of PDFs (see
 * DepartmentOnboardingService and docs/DEPARTMENT_ONBOARDING.md). The work is blocking -- a crawl,
 * downloads and a corpus re-embed -- so it runs on boundedElastic and the request stays open until
 * the department is fully live.
 */
@RestController
@RequestMapping("/admin/departments")
public class DepartmentOnboardingController {

    private final DepartmentOnboardingService onboardingService;

    public DepartmentOnboardingController(DepartmentOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public Mono<ResponseEntity<DepartmentOnboardingResult>> onboard(
            @Valid @RequestBody DepartmentOnboardingRequest request) {
        return Mono.fromCallable(() -> onboardingService.onboard(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
