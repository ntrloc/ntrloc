package org.ntrloc.ui.hosting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Discovers admin UI extensions by classpath convention -- classpath*:/static/admin-ui-extensions/
// <key>/manifest.json, one directory per extension, contributed by any JAR on the classpath (this
// starter's own, a use-case-specific one like ntrloc-dam, or a user's own ad-hoc module) -- the
// same "just show up on the classpath" discovery UiHostingProperties.ADMIN_UI_DEFAULT_LOCATION
// already uses for the bundled admin-ui itself, just pluralized with classpath*: across every JAR
// instead of one fixed classpath: location. No Java registration required: an extension author
// drops a built bundle.js and a small manifest.json at the conventional path and it's found
// automatically.
//
// bundle.js is expected to be an IIFE build that expects react, react-dom and react-router-dom as
// globals on window, set by the core admin UI's own bootstrap before any extension script loads --
// avoids every extension bundling its own copy of React.
@Component
public class AdminUiExtensionScanner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminUiExtensionScanner.class);
    private static final String MANIFEST_PATTERN = "classpath*:/static/admin-ui-extensions/*/manifest.json";
    private static final Pattern KEY_PATTERN = Pattern.compile("admin-ui-extensions/([^/]+)/manifest\\.json");

    private final List<AdminUiExtensionDescriptor> extensions;

    public AdminUiExtensionScanner(ObjectMapper objectMapper) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        List<AdminUiExtensionDescriptor> found = new ArrayList<>();
        for (Resource resource : resolver.getResources(MANIFEST_PATTERN)) {
            String key = extractKey(resource);
            if (key == null) {
                LOG.warn("Could not determine extension key for manifest at {}", resource);
                continue;
            }
            try (var in = resource.getInputStream()) {
                ManifestContent manifest = objectMapper.readValue(in, ManifestContent.class);
                found.add(new AdminUiExtensionDescriptor(manifest.navLabel(), manifest.navPath(),
                        "/admin-ui-extensions/" + key + "/bundle.js"));
            } catch (Exception e) {
                LOG.warn("Failed to load admin UI extension manifest for '{}'", key, e);
            }
        }
        this.extensions = List.copyOf(found);
        if (!this.extensions.isEmpty()) {
            LOG.info("Discovered {} admin UI extension(s): {}", extensions.size(),
                    extensions.stream().map(AdminUiExtensionDescriptor::navLabel).toList());
        }
    }

    public List<AdminUiExtensionDescriptor> extensions() {
        return extensions;
    }

    private String extractKey(Resource resource) {
        Matcher matcher = KEY_PATTERN.matcher(String.valueOf(resource.getDescription()));
        return matcher.find() ? matcher.group(1) : null;
    }

    private record ManifestContent(String navLabel, String navPath) {}
}
