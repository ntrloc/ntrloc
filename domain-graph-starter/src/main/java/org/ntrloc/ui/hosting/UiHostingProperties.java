package org.ntrloc.ui.hosting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

// Multi-mount static UI hosting: each mount serves a directory of precompiled/static UI assets
// (an Angular/React/etc. build output, or hand-written HTML/JS) at a configurable path prefix,
// with an index.html fallback for client-side routing. General-purpose -- any domain runtime can
// register as many mounts as it needs, each independently configured.
//
// The bundled admin-ui is the one exception to "general-purpose, no built-in opinion": every
// domain runtime gets it mounted at ADMIN_UI_PATH for free, from this starter's own classpath,
// so a runtime with no graph.ui config at all still has a working admin console. A runtime that
// declares its own mount at ADMIN_UI_PATH (to point at a different build, e.g. a local checkout
// for live-reload during admin-ui development, or to replace it with a custom UI entirely)
// overrides the default rather than colliding with it.
@ConfigurationProperties(prefix = "graph.ui")
public record UiHostingProperties(List<Mount> mounts) {

    public static final String ADMIN_UI_PATH = "/admin";
    public static final String ADMIN_UI_DEFAULT_LOCATION = "classpath:/static/admin-ui/";

    public UiHostingProperties {
        mounts = mounts == null ? List.of() : mounts;
        if (mounts.stream().noneMatch(m -> normalize(m.path()).equals(ADMIN_UI_PATH))) {
            List<Mount> withDefault = new ArrayList<>(mounts);
            withDefault.add(new Mount(ADMIN_UI_PATH, ADMIN_UI_DEFAULT_LOCATION));
            mounts = List.copyOf(withDefault);
        }
    }

    private static String normalize(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    // path is the URL prefix this mount is served at (e.g. "/admin"). location is a Spring
    // resource location (classpath:... or file:...) pointing at the directory of assets.
    public record Mount(String path, String location) {
    }
}
