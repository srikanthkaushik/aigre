package com.aigre.embed;

import com.aigre.classification.DepartmentDirectory;
import com.aigre.config.DomainProperties;
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
 * With aigre.domain.agency-wide-embed=true (the dmv profile), an embed with NO department is
 * allowed too, framed by the union of every department's registered origins: in a single-agency
 * deployment every registered origin belongs to that agency, and an unscoped widget searches the
 * whole corpus. Off by default, because in the city profile it would let a site registered for one
 * department frame a widget answering for all of them. An unknown department id is still 'none'.
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
    private final boolean agencyWideEmbed;

    public EmbedChatController(
            EmbedOriginService embedOriginService,
            DepartmentDirectory departmentDirectory,
            DomainProperties domain) {
        this.embedOriginService = embedOriginService;
        this.departmentDirectory = departmentDirectory;
        this.agencyWideEmbed = domain.agencyWideEmbed();
    }

    @GetMapping(value = "/embed/chat", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<Resource>> embedChat(@RequestParam(required = false) String department) {
        return Mono.fromCallable(() -> {
            List<String> origins;
            if (department == null || department.isBlank()) {
                origins = agencyWideEmbed ? embedOriginService.listAllOrigins() : List.of();
            } else if (departmentDirectory.departmentIds().contains(department)) {
                origins = embedOriginService.listOrigins(department);
            } else {
                origins = List.of();
            }
            String frameAncestors = origins.isEmpty() ? "'none'" : String.join(" ", origins);

            return ResponseEntity.ok()
                    .header("Content-Security-Policy", "frame-ancestors " + frameAncestors)
                    .contentType(MediaType.TEXT_HTML)
                    .body((Resource) new ClassPathResource("static/index.html"));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
