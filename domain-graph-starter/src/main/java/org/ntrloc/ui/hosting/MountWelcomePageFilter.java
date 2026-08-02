package org.ntrloc.ui.hosting;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;

// Spring's reactive resource handler rejects an empty resource path before the resolver chain
// (and its SpaFallbackResourceResolver fallback) ever runs -- so a request for exactly a mount's
// root (e.g. "/admin" or "/admin/") 404s instead of serving index.html, even though any non-empty
// sub-path correctly falls back. This rewrites the request path to .../index.html for exactly the
// trailing-slash root request, before resource handling sees it.
//
// The slash-less root request (e.g. "/admin") gets a real redirect to the trailing-slash form
// instead, rather than also being rewritten in place: index.html's own asset references are
// relative (e.g. "vendor/alpinejs.min.js"), which only resolve correctly against the mount's own
// directory as the browser's URL bar sees it. Serving index.html's bytes at the slash-less URL
// without an actual redirect would leave the browser resolving those relative paths against the
// mount's parent directory instead, breaking every asset load.
public class MountWelcomePageFilter implements WebFilter {

    private final UiHostingProperties properties;

    public MountWelcomePageFilter(UiHostingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        for (UiHostingProperties.Mount mount : properties.mounts()) {
            String mountPath = mount.path().endsWith("/") ? mount.path().substring(0, mount.path().length() - 1) : mount.path();

            if (path.equals(mountPath)) {
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.FOUND);
                response.getHeaders().setLocation(URI.create(mountPath + "/"));
                return response.setComplete();
            }

            if (path.equals(mountPath + "/")) {
                ServerWebExchange rewritten = exchange.mutate()
                        .request(exchange.getRequest().mutate().path(mountPath + "/index.html").build())
                        .build();
                return chain.filter(rewritten);
            }
        }
        return chain.filter(exchange);
    }
}
