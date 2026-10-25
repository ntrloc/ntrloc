package org.ntrloc.graph.events;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// completeAll() is what lets the application shut down without waiting out graceful shutdown's
// timeout for SSE streams no client will ever close (see its own comment).
class SseEmitterRegistryTest {

    private final SseEmitterRegistry<String> registry = new SseEmitterRegistry<>(type -> SseEmitter.event().data(type));

    @Test
    void completeAll_completesEveryOpenStream() {
        SseEmitter first = registry.register();
        SseEmitter second = registry.register();

        registry.completeAll();

        assertThatThrownBy(() -> first.send("after shutdown")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> second.send("after shutdown")).isInstanceOf(IllegalStateException.class);
    }
}
