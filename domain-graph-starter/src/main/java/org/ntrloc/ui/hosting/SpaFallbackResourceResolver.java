package org.ntrloc.ui.hosting;

import org.springframework.core.io.Resource;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;

// Falls back to index.html within the same mount when the requested path doesn't resolve to a
// real file and doesn't look like one (no dot in the last path segment) -- lets a mounted SPA's
// own client-side router handle paths like /admin/schema that have no literal file on disk,
// without masking genuine 404s for missing assets (e.g. a real but absent .js file).
public class SpaFallbackResourceResolver extends PathResourceResolver {

    @Override
    protected Mono<Resource> getResource(String resourcePath, Resource location) {
        return super.getResource(resourcePath, location)
                .switchIfEmpty(Mono.defer(() -> looksLikeFile(resourcePath)
                        ? Mono.empty()
                        : super.getResource("index.html", location)));
    }

    private boolean looksLikeFile(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? resourcePath.substring(lastSlash + 1) : resourcePath;
        return lastSegment.contains(".");
    }
}
