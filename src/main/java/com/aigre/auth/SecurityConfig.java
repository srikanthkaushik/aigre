package com.aigre.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Real employee auth (JWT, tied to department_employees) replacing the milestone-5 demo-only
 * department picker -- see PROJECT.md. Citizen-facing endpoints (submit/status/clarify/reopen,
 * chat) stay public: citizens never log in. Everything under the employee dashboard requires a
 * valid employee bearer token; the two mutation endpoints (resume a paused review, mark
 * resolved/closed) additionally require the SUPERVISOR or ADMIN role -- an AGENT can view their
 * department's queue but not act on it. ADMIN is a cross-department oversight role (null
 * departmentId): DepartmentAccess.requireOwnDepartment bypasses its own-department check for it,
 * and GrievanceQueryService.list() already treats a null department as "no filter" -- so ADMIN
 * needs no new pathMatchers rules beyond being included here, alongside SUPERVISOR.
 *
 * <p>The built Angular app is also served from this backend (SpaWebFluxConfig) so the whole app
 * is one origin/port -- its static assets and client-side route shells are permitted below
 * alongside the API rules; this has no bearing on employee auth, which is still enforced by the
 * API calls those pages make, not by the page load itself.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtService jwtService) {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // CORS preflight (OPTIONS) requests carry no Authorization header and must
                        // always be allowed through, or the browser never gets far enough to see
                        // the actual request's own auth rules -- WebConfig's WebFluxConfigurer-level
                        // CORS mapping ran too late to matter once Spring Security's own filter
                        // chain is in front of everything, which is why this is here instead.
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/grievances", "/grievances/workflow").permitAll()
                        // Must come before the "/grievances/{id}" permitAll rule below --
                        // {id} matches any single path segment, including the literal
                        // "trends", which would otherwise make this employee-only endpoint
                        // accidentally public (authorizeExchange matches in declaration
                        // order, first match wins).
                        .pathMatchers(HttpMethod.GET, "/grievances/trends").authenticated()
                        .pathMatchers(HttpMethod.GET, "/grievances/{id}").permitAll()
                        .pathMatchers(HttpMethod.POST, "/grievances/{id}/workflow/clarify").permitAll()
                        .pathMatchers(HttpMethod.POST, "/grievances/{id}/reopen").permitAll()
                        .pathMatchers("/chat/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/ingest/**").permitAll()
                        .pathMatchers("/mcp/**").permitAll()
                        // Public: department-name.pipe.ts renders on citizen.html, the
                        // unauthenticated citizen status page.
                        .pathMatchers(HttpMethod.GET, "/departments").permitAll()
                        // Creating a new department/routing target is bigger blast-radius than
                        // day-to-day supervisor work -- ADMIN only, not SUPERVISOR too.
                        .pathMatchers(HttpMethod.POST, "/admin/departments").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/grievances/{id}/workflow/resume", "/grievances/{id}/status")
                        .hasAnyRole("SUPERVISOR", "ADMIN")
                        // The built Angular app (see SpaWebFluxConfig), served from this same
                        // origin/port -- static assets plus its client-side routes. Everything an
                        // employee actually sees still requires its own JWT, checked client-side
                        // by auth.guard.ts and re-checked server-side by every /grievances,
                        // /auth call above; this just lets the page shell itself load.
                        .pathMatchers(
                                HttpMethod.GET,
                                "/",
                                "/login",
                                "/citizen/**",
                                "/employee/**",
                                "/*.js",
                                "/*.css",
                                "/favicon.ico",
                                "/assets/**")
                        .permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(new JwtAuthenticationWebFilter(jwtService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Single source of truth for CORS now -- previously a WebFluxConfigurer bean
     * (com.aigre.config.WebConfig, removed), which stopped mattering once Spring Security's own
     * filter chain sits in front of every request: WebFlux-level CORS mapping runs too late for a
     * preflight request that Security has already rejected. Allows any localhost port (not just
     * one hardcoded port) since `ng serve` falls back to the next free port when its default is
     * already taken by an unrelated project on this machine.
     *
     * <p>Also allows any *.trycloudflare.com origin -- browsers fetch `<script type="module">`
     * (what the Angular build emits) in CORS mode, sending an Origin header even for genuinely
     * same-origin requests. Spring's CorsProcessor 403s outright (empty body, before the request
     * even reaches the permitAll rules below) if that Origin isn't in this list, which is exactly
     * what broke every JS asset when the app was reached through a Cloudflare quick tunnel instead
     * of localhost -- the random *.trycloudflare.com subdomain wasn't allowed. The subdomain
     * changes every `cloudflared` restart but the trycloudflare.com suffix doesn't, so a wildcard
     * pattern here doesn't need updating each time.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.trycloudflare.com"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
