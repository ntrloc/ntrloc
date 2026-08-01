package org.ntrloc.graph.ai;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Map;

// Shared by McpStreamableTransportContextConfig and McpSseTransportContextConfig -- see either
// class's own comment for why this needs to exist and why it's split into two.
final class McpTransportContextConfig {

    static final String AUTHORIZATION_TRANSPORT_KEY = "authorization";

    private McpTransportContextConfig() {
    }

    static McpTransportContext extractAuthorizationHeader(ServerRequest request) {
        String header = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        return header == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(AUTHORIZATION_TRANSPORT_KEY, header));
    }
}
