package org.ntrloc.graph.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class CsrfController {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfController.class);

    @GetMapping("/api/csrf")
    public Mono<Map<String, String>> csrf(ServerWebExchange exchange) {
        try {
            return exchange.getAttributeOrDefault(
                            CsrfToken.class.getName(), Mono.empty())
                    .cast(CsrfToken.class)
                    .map(token -> Map.of(
                            "token", token.getToken(),
                            "header", token.getHeaderName(),
                            "parameter", token.getParameterName()
                    ));
        } catch (Exception e) {
            LOG.warn("Failed to resolve CSRF token", e);
            return Mono.empty();
        }
    }
}
