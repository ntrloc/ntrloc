package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.RuntimeService;
import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;
import org.ntrloc.graph.db.partition.binary.event.BinaryContentListener;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

// Translates "a binary was genuinely just created" into "start the extraction process" -- the one
// place this codebase currently reacts to a domain event by starting a BPMN process (see
// extract-binary-metadata.bpmn20.xml and ExtractBinaryMetadataDelegate). Starting a process instance
// is cheap (a handful of row inserts), so this stays synchronous; the actual media-processor call is
// what needs to be off this thread, and that's the service task's own job (flowable:async="true"),
// not this listener's.
@Component
public class BinaryMetadataExtractionTrigger implements BinaryContentListener {

    static final String PROCESS_KEY = "extractBinaryMetadata";
    static final String BINARY_CONTENT_ID_VARIABLE = "binaryContentId";

    private final RuntimeService runtimeService;

    public BinaryMetadataExtractionTrigger(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    @EventListener
    public void onBinaryContentEvent(BinaryContentEvent event) {
        if (event instanceof BinaryContentEvent.Created e) {
            runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                    Map.of(BINARY_CONTENT_ID_VARIABLE, e.binaryContentId().toString()));
        }
    }
}
