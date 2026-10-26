package org.ntrloc.graph.security;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Keycloak marks its session cookies Secure. Browsers treat localhost as a secure origin and
// send them over plain HTTP anyway; java.net.CookieManager doesn't, so without this Keycloak
// never sees its own login session and rejects the form post.
public final class LocalhostCookieHandler extends CookieHandler {

    private final CookieManager delegate;

    public LocalhostCookieHandler(CookieManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
        return delegate.get(uri, requestHeaders);
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        Map<String, List<String>> withoutSecure = new HashMap<>(responseHeaders);
        withoutSecure.replaceAll((name, values) -> name != null && name.equalsIgnoreCase("Set-Cookie")
                ? values.stream().map(value -> value.replaceAll("(?i);\\s*Secure", "")).toList()
                : values);
        delegate.put(uri, withoutSecure);
    }
}
