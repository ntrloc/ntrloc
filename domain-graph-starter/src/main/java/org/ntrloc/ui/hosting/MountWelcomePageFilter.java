package org.ntrloc.ui.hosting;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Spring's static resource handler rejects an empty resource path before the resolver chain
// (and its SpaFallbackResourceResolver fallback) ever runs -- so a request for exactly a mount's
// root (e.g. "/admin" or "/admin/") 404s instead of serving index.html, even though any non-empty
// sub-path correctly falls back. This forwards the request to .../index.html for exactly the
// trailing-slash root request, before resource handling sees it.
//
// The slash-less root request (e.g. "/admin") gets a real redirect to the trailing-slash form
// instead, rather than also being forwarded in place: index.html's own asset references are
// relative (e.g. "vendor/alpinejs.min.js"), which only resolve correctly against the mount's own
// directory as the browser's URL bar sees it. Serving index.html's bytes at the slash-less URL
// without an actual redirect would leave the browser resolving those relative paths against the
// mount's parent directory instead, breaking every asset load.
public class MountWelcomePageFilter extends OncePerRequestFilter {

    private final UiHostingProperties properties;

    public MountWelcomePageFilter(UiHostingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();

        for (UiHostingProperties.Mount mount : properties.mounts()) {
            String mountPath = mount.path().endsWith("/") ? mount.path().substring(0, mount.path().length() - 1) : mount.path();

            if (path.equals(mountPath)) {
                response.sendRedirect(mountPath + "/");
                return;
            }

            if (path.equals(mountPath + "/")) {
                request.getRequestDispatcher(mountPath + "/index.html").forward(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
