package com.aigre.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient.Builder isn't auto-configured in this app (confirmed empirically: PdfCrawlService's
 * constructor injection failed with NoSuchBeanDefinitionException without this) -- declared
 * explicitly rather than relying on autoconfiguration that isn't kicking in.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
