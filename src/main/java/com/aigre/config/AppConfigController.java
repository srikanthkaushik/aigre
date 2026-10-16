package com.aigre.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (see SecurityConfig): the frontend loads this once at startup so the same build can show
 * "Departments" and city copy, or "Bureaus" and DMV copy, depending on the backend's domain profile.
 */
@RestController
@EnableConfigurationProperties(DomainProperties.class)
public class AppConfigController {

    private final DomainProperties domain;

    public AppConfigController(DomainProperties domain) {
        this.domain = domain;
    }

    @GetMapping("/app-config")
    public DomainProperties appConfig() {
        return domain;
    }
}
