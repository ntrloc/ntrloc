package org.ntrloc.graph.events;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

// New, parallel SSE relay proving out ClusterEventBroadcaster end to end (schemaChanged only, for
// now) alongside the existing /api/admin/schema/events, which this does not touch. Each client
// declares which event types it wants via ?types=; filtering happens server-side, per connection,
// before anything is written to that client's stream -- no scoping predicate needed yet since
// schemaChanged carries no client-specific scope (every authenticated user sees it).
//
// No explicit security config: falls under SecurityConfig's default .anyRequest().authenticated()
// same as every other endpoint not under /admin/** or the open paths.
//
// The heartbeat used to be built here, merged per-connection into each subscriber's own Flux --
// it's now ClusterEventBroadcaster's concern instead (one shared scheduled task for every current
// connection, see that class's own comment), since filtering is the only thing actually specific
// to a given connection.
@RestController
public class ClusterEventController {

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
    SseEmitter events(@RequestParam List<String> types, Authentication authentication) {
        Set<String> requestedTypes = Set.copyOf(types);
        NtrlocPrincipal principal = principalResolver.resolve(authentication);
        return broadcaster.register(event -> requestedTypes.contains(event.type())
                && visibilityRegistry.isVisible(event, principal));
    }
}
