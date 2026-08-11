package com.aigre.workflow;

import com.aigre.intake.GrievanceIntakeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * The agent-workflow counterpart to GrievanceController's direct intake: confident/unambiguous
 * cases auto-commit exactly like plain intake, but low-confidence or ambiguous cases pause for a
 * supervisor's decision instead of parking indefinitely in NEEDS_CLARIFICATION.
 */
@RestController
@RequestMapping("/grievances")
public class GrievanceWorkflowController {

    private final GrievanceWorkflowService service;

    public GrievanceWorkflowController(GrievanceWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/workflow")
    public Mono<GrievanceWorkflowResponse> start(@Valid @RequestBody GrievanceIntakeRequest request) {
        return Mono.fromCallable(() -> service.start(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/workflow")
    public Mono<GrievanceWorkflowResponse> status(@PathVariable UUID id) {
        return Mono.fromCallable(() -> service.status(id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/workflow/resume")
    public Mono<GrievanceWorkflowResponse> resume(
            @PathVariable UUID id, @Valid @RequestBody GrievanceReviewDecision decision) {
        return Mono.fromCallable(() -> service.resume(id, decision))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/workflow/clarify")
    public Mono<GrievanceWorkflowResponse> clarify(
            @PathVariable UUID id, @Valid @RequestBody ClarificationRequest request) {
        return Mono.fromCallable(() -> service.clarify(id, request.additionalText()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
