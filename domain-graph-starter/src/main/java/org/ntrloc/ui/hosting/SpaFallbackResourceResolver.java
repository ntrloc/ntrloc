package org.ntrloc.ui.hosting;

import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

// Falls back to index.html within the same mount when the requested path doesn't resolve to a
// real file and doesn't look like one (no dot in the last path segment) -- lets a mounted SPA's
// own client-side router handle paths like /admin/schema that have no literal file on disk,
// without masking genuine 404s for missing assets (e.g. a real but absent .js file).
public class SpaFallbackResourceResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = super.getResource(resourcePath, location);
        if (resource != null) {
            return resource;
        }
        return looksLikeFile(resourcePath) ? null : super.getResource("index.html", location);
    }

    private boolean looksLikeFile(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? resourcePath.substring(lastSlash + 1) : resourcePath;
        return lastSegment.contains(".");
    }
}
