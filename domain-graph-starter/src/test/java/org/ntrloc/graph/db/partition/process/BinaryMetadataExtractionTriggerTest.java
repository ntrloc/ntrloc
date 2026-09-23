package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A plain unit test, not a Spring context -- the trigger's own job is narrowly "translate this
// event into this specific startProcessInstanceByKey call." Whether the process then actually
// runs correctly (deploys, pauses at its async continuation, the job executor picks it up) is a
// BPMN/Flowable-framework concern proved separately by BinaryMetadataExtractionTriggerIntegrationTest,
// not something to re-verify here by racing the real, shared async job executor -- confirmed
// empirically fast enough to complete a no-op delegate run (a fake binaryContentId) within
// milliseconds of starting, before a test's very next assertion could observe the instance as
// still running.
class BinaryMetadataExtractionTriggerTest {

    @Test
    void binaryContentCreatedEvent_startsTheExtractionProcessWithTheBinaryContentIdVariable() {
        RuntimeService runtimeService = mock(RuntimeService.class);
        when(runtimeService.startProcessInstanceByKey(eq(BinaryMetadataExtractionTrigger.PROCESS_KEY), anyMap()))
                .thenReturn(mock(ProcessInstance.class));
        BinaryMetadataExtractionTrigger trigger = new BinaryMetadataExtractionTrigger(runtimeService);
        UUID binaryContentId = UUID.randomUUID();

        trigger.onBinaryContentEvent(new BinaryContentEvent.Created(binaryContentId));

        verify(runtimeService).startProcessInstanceByKey(
                BinaryMetadataExtractionTrigger.PROCESS_KEY,
                Map.of(BinaryMetadataExtractionTrigger.BINARY_CONTENT_ID_VARIABLE, binaryContentId.toString()));
    }
}
