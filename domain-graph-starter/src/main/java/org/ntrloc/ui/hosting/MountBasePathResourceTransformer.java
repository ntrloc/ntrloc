package org.ntrloc.ui.hosting;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Vite can't know a mount's real runtime path at build time -- a UI's production build instead
// bakes a literal placeholder into an injected <base href> tag (see ntrloc-admin-ui's own
// vite.config.js), and this is the one place that gets replaced with the REAL configured mount
// path, at serve time, for index.html specifically. Every relative asset reference and link in
// that document resolves against whatever <base href> says, not the document's own request URL
// -- which is what lets one build (never rebuilt per consumer, per mount) serve correctly at
// whatever path a given runtime configures, including a deep client-side route served fresh via
// SpaFallbackResourceResolver, where a plain relative path would otherwise resolve against the
// wrong directory.
//
// A no-op for any resource that doesn't actually contain the placeholder -- every non-HTML
// asset, and the vanilla-JS admin-ui's own index.html, which was never built with it. Safe to
// register on every mount uniformly; this class doesn't need to know which mount(s) actually use
// the mechanism.
public class MountBasePathResourceTransformer implements ResourceTransformer {

    static final String PLACEHOLDER = "__NTRLOC_UI_BASE_PATH__";

    private final String basePath;

    public MountBasePathResourceTransformer(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain transformerChain) throws IOException {
        Resource transformed = transformerChain.transform(request, resource);
        if (!"index.html".equals(transformed.getFilename())) {
            return transformed;
        }

        String content = new String(transformed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!content.contains(PLACEHOLDER)) {
            return transformed;
        }

        byte[] replaced = content.replace(PLACEHOLDER, basePath).getBytes(StandardCharsets.UTF_8);
        return new ByteArrayResource(replaced) {
            @Override
            public String getFilename() {
                return transformed.getFilename();
            }

            // AbstractResource.lastModified()'s default delegates to getFile(), which
            // ByteArrayResource can't support (no backing file at all) and throws
            // FileNotFoundException instead -- ResourceHttpRequestHandler calls this for
            // Last-Modified/conditional-GET support on every resource it serves, turning that
            // into a real 500 for exactly this resource. There's no real file to report a
            // timestamp for, so treat it as modified right now, same as any other
            // freshly-computed response.
            @Override
            public long lastModified() {
                return System.currentTimeMillis();
            }
        };
    }
}
