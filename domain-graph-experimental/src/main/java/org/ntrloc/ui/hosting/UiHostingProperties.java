package org.ntrloc.ui.hosting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// Multi-mount static UI hosting: each mount serves a directory of precompiled/static UI assets
// (an Angular/React/etc. build output, or hand-written HTML/JS) at a configurable path prefix,
// with an index.html fallback for client-side routing. General-purpose -- any domain runtime can
// register as many mounts as it needs, each independently configured.
@ConfigurationProperties(prefix = "ntrloc.ui")
public record UiHostingProperties(List<Mount> mounts) {

    public UiHostingProperties {
        mounts = mounts == null ? List.of() : mounts;
    }

    // path is the URL prefix this mount is served at (e.g. "/admin"). location is a Spring
    // resource location (classpath:... or file:...) pointing at the directory of assets.
    public record Mount(String path, String location) {
    }
}
