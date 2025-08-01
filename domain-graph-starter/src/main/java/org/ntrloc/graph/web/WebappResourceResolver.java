package org.ntrloc.graph.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.resource.ResourceResolver;
import org.springframework.web.reactive.resource.ResourceResolverChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

public class WebappResourceResolver implements ResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(WebappResourceResolver.class);

    private Pattern fileWithExtensionPattern = Pattern.compile(".+\\.[\\w]+$");

    @Override
    public Mono<Resource> resolveResource(ServerWebExchange exchange, String requestPath, List<? extends Resource> locations, ResourceResolverChain chain) {
        String[] pathParts = requestPath.split("/");
        String resource = pathParts[pathParts.length - 1];
        if (fileWithExtensionPattern.matcher(resource).matches()) {
            LOG.debug("Using file resource {}", resource);
        } else {
            LOG.debug("Using template resource {}", resource);
        }
        return null;
    }

    @Override
    public Mono<String> resolveUrlPath(String resourcePath, List<? extends Resource> locations, ResourceResolverChain chain) {
        return null;
    }

}
