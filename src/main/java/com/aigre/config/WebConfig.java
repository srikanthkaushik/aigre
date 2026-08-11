package com.aigre.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * CORS for the Angular dev server (milestone 5) — ng serve runs on a different origin than the
 * Spring Boot app (localhost:8085). Allows any localhost port, not just one hardcoded port,
 * since ng serve falls back to the next free port (4200 was already taken on this machine by an
 * unrelated project, so this instance actually runs on 4300).
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
