package org.ntrloc.graph;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class TestController {

    @GetMapping("/public")
    public Mono<String> publicEndpoint() {
        return Mono.just("This is public");
    }

    @GetMapping("/me")
    public Mono<Map<String, Object>> me(@AuthenticationPrincipal Object principal) {
        if (principal instanceof OidcUser user) {
            return Mono.just(Map.of(
                    "type",     "oidc",
                    "subject",  user.getSubject(),
                    "email",    user.getEmail(),
                    "username", user.getPreferredUsername()
            ));
        } else if (principal instanceof UserDetails user) {
            return Mono.just(Map.of(
                    "type",     "local",
                    "username", user.getUsername()
            ));
        }
        return Mono.just(Map.of("type", "unknown"));
    }

}
