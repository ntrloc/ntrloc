package org.ntrloc.graph.db.partition.process;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// End to end: BinaryPartitionManagerImpl.store() -> BinaryContentEvent.Created (only on a genuine
// fresh insert) -> BinaryMetadataExtractionTrigger -> extractBinaryMetadata process started.
//
// Diffs the *set* of process_job ids immediately before/after store(), not a raw row count --
// RuntimeService.createProcessInstanceQuery() races the real, already-running async job executor
// (config.setAsyncExecutorActivate(true)), which is fast enough to pick up and complete a no-op
// delegate run (this test's content never has an image mimeType, so the delegate returns immediately
// once it runs) within milliseconds of starting -- with history off, a completed instance leaves no
// query-able trace, so a raw count is vulnerable to *other* tests' leftover, not-yet-consumed jobs
// (every store() call in this whole suite goes through the same shared trigger) disappearing mid-test
// and throwing the delta off -- confirmed empirically: adding an unrelated test class whose own
// store() calls happened to sort earlier alphabetically made this exact test fail, purely from that
// class's leftover jobs being swept during this test's own store() call, nothing to do with this
// test's own correctness. A set difference is immune to that: any pre-existing id, whether or not it
// survives to the "after" read, is excluded either way, so only a genuinely new id (this test's own)
// can show up in the diff -- reliable regardless of what else in the suite has called store() before
// it, in any order.
//
// Deliberately only one test here, proving the full pipeline is wired together end to end for a
// single fresh insert. The "dedup publishes the event only once" claim used to live here too (a
// second store() of identical content, asserting no second job appeared) but that's a claim about
// BinaryPartitionManagerImpl.insert()'s own fresh-insert detection, not about Flowable -- proving it
// via job-table evidence means racing the async executor across two separate actions, which stays
// unreliable no matter how the reads bracket each action (confirmed: the executor can sweep the first
// store()'s no-op job while the second store() is still doing its own real hashing/disk I/O). See
// BinaryContentEventPublishingIntegrationTest, in the binary package, which proves that claim at the
// deterministic event-publish boundary instead.
class BinaryMetadataExtractionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private JdbcClient jdbcClient;

    private static Flux<DataBuffer> bufferFlux(byte[] content) {
        return Flux.just(new DefaultDataBufferFactory().wrap(content));
    }

    @Test
    void store_forNewContent_startsTheExtractionProcess() {
        Set<String> jobsBefore = jobIds();
        byte[] content = ("extraction trigger test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        binaryPartitionManager.store(bufferFlux(content)).block();

        Set<String> newJobs = new HashSet<>(jobIds());
        newJobs.removeAll(jobsBefore);
        assertThat(newJobs).hasSize(1);
    }

    private Set<String> jobIds() {
        return new HashSet<>(jdbcClient.sql("SELECT id FROM process_job").query(String.class).list());
    }
}
