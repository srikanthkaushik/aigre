package com.aigre.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;

/**
 * Serves the built Angular app (frontend/dist/frontend/browser, copied into
 * src/main/resources/static -- see RUNNING.md) from this same backend, so the whole app is one
 * origin on one port. Falls back to index.html for any path that isn't a real static file (e.g.
 * "/employee", "/citizen/xyz") so a direct load or refresh on an Angular client-side route works
 * instead of 404ing -- annotated @RestController mappings (/grievances, /auth, /chat, etc.) still
 * take precedence over this, since RequestMappingHandlerMapping is ordered ahead of the resource
 * handler registered here.
 */
@Configuration
public class SpaWebFluxConfig implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Mono<Resource> getResource(String resourcePath, Resource location) {
                        return super.getResource(resourcePath, location)
                                .switchIfEmpty(Mono.just(new ClassPathResource("static/index.html")));
                    }
                });
    }
}
