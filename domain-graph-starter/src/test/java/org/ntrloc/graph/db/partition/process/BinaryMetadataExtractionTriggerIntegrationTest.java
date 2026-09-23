package org.ntrloc.graph.db.partition.process;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Published directly (bypassing BinaryPartitionManagerImpl/the DB entirely) so this stays isolated
// to the listener's own behavior, not re-proving insert()'s own fresh-insert detection
// (BinaryContentEventPublishingIntegrationTest already covers that).
//
// Diffs the *set* of process_job ids immediately before/after publishing, not a raw row count --
// RuntimeService.createProcessInstanceQuery() races the real, shared, already-running async job
// executor (config.setAsyncExecutorActivate(true)), which is fast enough to pick up and complete a
// no-op delegate run (this test's binaryContentId doesn't back a real binary_content row, so the
// delegate returns immediately) within milliseconds of the process starting -- with history off, a
// completed instance leaves no query-able trace at all. A raw count is vulnerable to *other* tests'
// leftover, not-yet-consumed jobs (every store()/publishEvent() in this whole suite goes through the
// same shared trigger) disappearing mid-test and throwing a simple delta off, regardless of test
// ordering (see BinaryMetadataExtractionIntegrationTest's own comment for where this was caught in
// practice). A set difference is immune to that: any pre-existing id, whether or not it survives to
// the "after" read, is excluded either way, so only a genuinely new id (this test's own) can appear.
class BinaryMetadataExtractionTriggerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void binaryContentCreatedEvent_startsTheExtractionProcess() {
        Set<String> jobsBefore = jobIds();

        eventPublisher.publishEvent(new BinaryContentEvent.Created(UUID.randomUUID()));

        Set<String> newJobs = new HashSet<>(jobIds());
        newJobs.removeAll(jobsBefore);
        assertThat(newJobs).hasSize(1);
    }

    private Set<String> jobIds() {
        return new HashSet<>(jdbcClient.sql("SELECT id FROM process_job").query(String.class).list());
    }
}
