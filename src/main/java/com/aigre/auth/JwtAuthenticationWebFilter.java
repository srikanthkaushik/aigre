package com.aigre.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Registered only inside SecurityConfig's SecurityWebFilterChain (not @Component -- a plain
 * WebFilter bean would also be picked up by WebFlux's own generic filter registration, running
 * outside Spring Security's own chain and context propagation, same gotcha noted on
 * PiiRedactionWebFilter for a different reason). Validates the bearer token and, if valid,
 * populates the reactive SecurityContext for the rest of the chain -- unauthenticated requests
 * are passed through unchanged; Spring Security's authorizeExchange rules decide whether that's
 * allowed for the requested path.
 */
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtService jwtService;

    public JwtAuthenticationWebFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        return jwtService.parseToken(header.substring(7))
                .map(principal -> {
                    Authentication auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                    return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                })
                .orElseGet(() -> chain.filter(exchange));
    }
}
