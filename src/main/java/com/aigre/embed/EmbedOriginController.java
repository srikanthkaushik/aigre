package com.aigre.embed;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/** ADMIN-only (SecurityConfig) -- registers which external origins may embed a department's chat widget. */
@RestController
@RequestMapping("/admin/departments/{id}/embed-origins")
public class EmbedOriginController {

    private final EmbedOriginService embedOriginService;

    public EmbedOriginController(EmbedOriginService embedOriginService) {
        this.embedOriginService = embedOriginService;
    }

    @GetMapping
    public Mono<List<String>> list(@PathVariable String id) {
        return Mono.fromCallable(() -> embedOriginService.listOrigins(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<Void>> add(@PathVariable String id, @Valid @RequestBody EmbedOriginRequest request) {
        return Mono.fromRunnable(() -> embedOriginService.addOrigin(id, request.origin()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok().build());
    }

    @DeleteMapping
    public Mono<ResponseEntity<Void>> remove(@PathVariable String id, @RequestParam String origin) {
        return Mono.fromRunnable(() -> embedOriginService.removeOrigin(id, origin))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok().build());
    }
}
