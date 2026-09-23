package org.ntrloc.graph.db.partition.binary;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

// Proves BinaryPartitionManagerImpl.insert()'s own fresh-insert detection ("publish Created once
// per genuine insert, never on a dedup hit") directly at the event-publish boundary -- deliberately
// NOT via job-table row counts (see BinaryMetadataExtractionIntegrationTest, in the process package,
// for why that's the wrong tool for this specific claim): any assertion that depends on comparing job
// counts across two separate actions races Flowable's own already-running async job executor, which is
// fast enough to sweep the first no-op job while the second store() call is still doing its own real
// hashing/disk I/O -- confirmed empirically, and true regardless of how tightly the reads bracket each
// action. This sidesteps that entirely: the listener is registered directly on the running
// ApplicationContext (ConfigurableApplicationContext.addApplicationListener), not as a bean, so this
// class keeps sharing the exact same cached Spring context every other AbstractIntegrationTest
// subclass uses. A @TestConfiguration nested bean was tried first and rejected: it changes the bean
// graph, which forks a *second*, separately-bootstrapped context (its own ProcessEngine, its own async
// job executor thread) that ends up running concurrently against the same shared Postgres
// testcontainer as every other test class -- two independent executors racing to claim rows out of one
// process_job table, which is a strictly worse version of the exact cross-context race this whole file
// exists to avoid. Registered listeners aren't beans, so publishEvent() delivers a raw POJO wrapped as
// PayloadApplicationEvent -- unwrapped by hand below, same as @EventListener does automatically for
// BinaryMetadataExtractionTrigger's own production method.
class BinaryContentEventPublishingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private static Flux<DataBuffer> bufferFlux(byte[] content) {
        return Flux.just(new DefaultDataBufferFactory().wrap(content));
    }

    @Test
    void store_forNewContent_publishesBinaryContentEventCreatedExactlyOnce() {
        List<BinaryContentEvent.Created> events = recordCreatedEvents();
        byte[] content = ("event publish test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        binaryPartitionManager.store(bufferFlux(content)).block();

        assertThat(events).hasSize(1);
    }

    @Test
    void store_forIdenticalContentTwice_publishesTheEventOnlyOnce() {
        List<BinaryContentEvent.Created> events = recordCreatedEvents();
        byte[] content = ("dedup event publish test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        binaryPartitionManager.store(bufferFlux(content)).block();
        binaryPartitionManager.store(bufferFlux(content)).block();

        assertThat(events).hasSize(1);
    }

    // A fresh listener/list per test, scoped to only that test's own store() calls -- not a shared
    // field -- so there's no baseline to subtract and no risk of one test's events leaking into
    // another's count.
    private List<BinaryContentEvent.Created> recordCreatedEvents() {
        List<BinaryContentEvent.Created> events = new CopyOnWriteArrayList<>();
        applicationContext.addApplicationListener(new ApplicationListener<ApplicationEvent>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                if (event instanceof PayloadApplicationEvent<?> payloadEvent
                        && payloadEvent.getPayload() instanceof BinaryContentEvent.Created created) {
                    events.add(created);
                }
            }
        });
        return events;
    }
}
