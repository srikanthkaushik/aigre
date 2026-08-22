package com.aigre.embed;

import com.aigre.classification.DepartmentDirectory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Serves the same Angular SPA shell as SpaWebFluxConfig's static fallback, but through an
 * annotated controller (ordered ahead of that fallback) so this one path can carry a
 * per-department, dynamically-computed Content-Security-Policy: frame-ancestors header --
 * something a static resource handler can't do. A missing or unregistered department gets
 * "frame-ancestors 'none'": the browser simply won't render the iframe on any site, which is
 * the correct default, not an error state needing its own UI.
 *
 * X-Frame-Options: DENY (Spring Security's default everywhere else) is suppressed only for
 * /embed/** by a dedicated SecurityWebFilterChain (see SecurityConfig) -- without that, it would
 * block framing outright regardless of this header, since browsers honor whichever of the two is
 * more restrictive.
 */
@RestController
public class EmbedChatController {

    private final EmbedOriginService embedOriginService;
    private final DepartmentDirectory departmentDirectory;

    public EmbedChatController(EmbedOriginService embedOriginService, DepartmentDirectory departmentDirectory) {
        this.embedOriginService = embedOriginService;
        this.departmentDirectory = departmentDirectory;
    }

    @GetMapping(value = "/embed/chat", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<Resource>> embedChat(@RequestParam(required = false) String department) {
        return Mono.fromCallable(() -> {
            List<String> origins = department != null && departmentDirectory.departmentIds().contains(department)
                    ? embedOriginService.listOrigins(department)
                    : List.of();
            String frameAncestors = origins.isEmpty() ? "'none'" : String.join(" ", origins);

            return ResponseEntity.ok()
                    .header("Content-Security-Policy", "frame-ancestors " + frameAncestors)
                    .contentType(MediaType.TEXT_HTML)
                    .body((Resource) new ClassPathResource("static/index.html"));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
