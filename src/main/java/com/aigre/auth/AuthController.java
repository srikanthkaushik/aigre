package com.aigre.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@Valid @RequestBody LoginRequest request) {
        return Mono.<Object>fromCallable(() -> authService.login(request.username(), request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok)
                // Explicit body rather than relying on Spring Boot's default error-attribute
                // rendering (server.error.include-message) -- confirmed live that it wasn't
                // surfacing the reason text in this app's WebFlux error responses, not worth
                // chasing further for a login-failure message.
                .onErrorResume(BadCredentialsException.class, e ->
                        Mono.just(ResponseEntity.status(UNAUTHORIZED).body(Map.of("message", e.getMessage()))));
    }
}
