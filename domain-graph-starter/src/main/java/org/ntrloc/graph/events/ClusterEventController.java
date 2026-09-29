package org.ntrloc.graph.events;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;

// New, parallel SSE relay proving out ClusterEventBroadcaster end to end (schemaChanged only, for
// now) alongside the existing /api/admin/schema/events, which this does not touch. Each client
// declares which event types it wants via ?types=; filtering happens server-side, per connection,
// before anything is written to that client's stream -- no scoping predicate needed yet since
// schemaChanged carries no client-specific scope (every authenticated user sees it).
//
// No explicit security config: falls under SecurityConfig's default .anyExchange().authenticated()
// same as every other endpoint not under /admin/** or the open paths.
@RestController
public class ClusterEventController {

    // Comfortably under the common ~60s default idle-connection timeout used by most reverse
    // proxies/load balancers/Kubernetes Ingress controllers -- an SSE comment line (ignored by
    // EventSource's message/named-event handling, so it never reaches application listeners) is
    // enough to keep those from treating this connection as idle and killing it.
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final ClusterEventBroadcaster broadcaster;
    private final ClusterEventVisibilityRegistry visibilityRegistry;
    private final PrincipalResolver principalResolver;

    public ClusterEventController(ClusterEventBroadcaster broadcaster, ClusterEventVisibilityRegistry visibilityRegistry,
                                   PrincipalResolver principalResolver) {
        this.broadcaster = broadcaster;
        this.visibilityRegistry = visibilityRegistry;
        this.principalResolver = principalResolver;
    }

    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> events(@RequestParam List<String> types, ServerHttpRequest request,
                                          Authentication authentication) {
        Set<String> requestedTypes = Set.copyOf(types);
        NtrlocPrincipal principal = principalResolver.resolve(request, authentication);
        Flux<ServerSentEvent<String>> eventStream = broadcaster.events()
                .filter(event -> requestedTypes.contains(event.type()))
                .filter(event -> visibilityRegistry.isVisible(event, principal))
                .map(event -> ServerSentEvent.<String>builder(event.payload()).event(event.type()).build());
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<String>builder().comment("heartbeat").build());
        return Flux.merge(eventStream, heartbeat);
    }
}
