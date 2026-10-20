package org.ntrloc.ui.hosting;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceResolverChain;
import org.springframework.web.servlet.resource.ResourceTransformerChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// No Spring context needed -- ResourceTransformer is a plain interface, and the only other
// collaborator (ResourceTransformerChain) is trivial to stub directly, matching exactly how
// Spring's own resource-handling pipeline calls a single, final transformer (nothing further
// downstream to delegate to).
class MountBasePathResourceTransformerTest {

    // Pass-through: no other transformer is actually registered alongside this one in
    // UiHostingConfiguration, so "the rest of the chain" is a no-op here, same as it would be in
    // production for every mount this project actually configures.
    private static final ResourceTransformerChain PASS_THROUGH = new ResourceTransformerChain() {
        @Override
        public ResourceResolverChain getResolverChain() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Resource transform(HttpServletRequest request, Resource resource) {
            return resource;
        }
    };

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final MountBasePathResourceTransformer transformer = new MountBasePathResourceTransformer("/admin-react/");

    private Resource htmlResource(String content, String filename) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    @Test
    void indexHtmlContainingThePlaceholder_getsItReplacedWithTheRealMountPath() throws IOException {
        // No trailing slash after the placeholder here -- matching vite.config.js's own
        // injectBaseHrefPlaceholder exactly: basePath below already carries its own trailing
        // slash (UiHostingConfiguration's normalized mount path), so it's the complete
        // replacement value on its own.
        Resource input = htmlResource("<head>\n    <base href=\"" + MountBasePathResourceTransformer.PLACEHOLDER + "\">\n  </head>", "index.html");

        Resource result = transformer.transform(request, input, PASS_THROUGH);

        String content = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("<base href=\"/admin-react/\">");
        assertThat(content).doesNotContain(MountBasePathResourceTransformer.PLACEHOLDER);
    }

    @Test
    void nonIndexHtmlResource_isReturnedUnchanged_evenIfItSomehowContainsThePlaceholder() throws IOException {
        // Real assets never contain this placeholder in practice -- proving the filename check
        // actually gates the rewrite, not just "the content happened not to match."
        Resource input = htmlResource("/* " + MountBasePathResourceTransformer.PLACEHOLDER + " */", "app.js");

        Resource result = transformer.transform(request, input, PASS_THROUGH);

        String content = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains(MountBasePathResourceTransformer.PLACEHOLDER);
    }

    @Test
    void indexHtmlWithoutThePlaceholder_isReturnedUnchanged() throws IOException {
        // The vanilla-JS admin-ui's own index.html was never built with this placeholder at all
        // -- registering this transformer on every mount uniformly must be a safe no-op there.
        Resource input = htmlResource("<head></head>", "index.html");

        Resource result = transformer.transform(request, input, PASS_THROUGH);

        String content = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("<head></head>");
    }
}
