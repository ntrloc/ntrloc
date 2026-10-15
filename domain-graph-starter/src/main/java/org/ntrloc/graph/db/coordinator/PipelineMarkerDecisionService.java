package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineEvaluator;
import org.ntrloc.graph.access.PipelineSource;
import org.ntrloc.graph.access.WatchDerivation;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerPartitionManager;
import org.ntrloc.graph.db.partition.ledger.MarkerAttribution;
import org.ntrloc.graph.db.partition.ledger.PipelineAppliedMarker;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Marker decision pipeline evaluation -- the pipeline-model successor to the retired
// MarkerRuleEvaluationService/StateMarkerDecisionService. Evaluates every pipeline *watching* a
// fact that actually changed in this entry (see WatchDerivation / PipelineSource.pipelinesWatching)
// against that item's own about-to-be-committed properties, and folds the resulting marker
// add/remove decision into the SAME ledger entry as the property change that triggered it -- called
// from LedgerRegisterCoordinatorImpl.prepare(), BEFORE the ledger append, same slot the retired
// mechanisms used and for the same reason (see that class's own git history).
//
// A pipeline bound to this item's type but watching only facts that didn't change here is never
// fetched, deserialized, or evaluated -- "bound to the type" and "relevant to this change" are
// different questions, and only the second one gates execution.
//
// registerPartitionManager doubles as PipelineEvaluator's FactResolver -- a CountedPredicate whose
// subject steps off a link (e.g. "do any of this item's photos have a portfolio in state X") now
// resolves through the same real register reads every other traversal in this class already uses,
// rather than ContextResolver's own still-standing StepToLink gap (reading a link's own properties,
// not stepping past it to an item) throwing UnsupportedOperationException.
@Service
public class PipelineMarkerDecisionService {

    private final PipelineSource pipelineSource;
    private final RegisterPartitionManager registerPartitionManager;
    private final LedgerPartitionManager ledgerPartitionManager;

    public PipelineMarkerDecisionService(PipelineSource pipelineSource, RegisterPartitionManager registerPartitionManager,
                                          LedgerPartitionManager ledgerPartitionManager) {
        this.pipelineSource = pipelineSource;
        this.registerPartitionManager = registerPartitionManager;
        this.ledgerPartitionManager = ledgerPartitionManager;
    }

    /** Pure: returns entries with markersAdded/markersRemoved (or initialMarkers) enriched wherever a pipeline watching one of this entry's actually-changed facts has a changed decision. Entries touching nothing any bound pipeline watches pass through unchanged. */
    public List<LedgerEntry> enrichWithPipelineDecisions(List<LedgerEntry> entries) {
        return entries.stream().map(this::enrichEntry).toList();
    }

    private LedgerEntry enrichEntry(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry e) return enrichCreate(e);
        if (entry instanceof ItemUpdateEntry e) return enrichUpdate(e);
        return entry;
    }

    // Everything a create sets is, by definition, newly "changed" -- there's no prior state to
    // diff against. ITEM_CREATED is always included too, so an unconditional pipeline (no real
    // signals at all, see WatchDerivation) -- which only ever watches that sentinel -- gets its one
    // and only trigger here. An update never includes it (see changedFacts(ItemUpdateEntry) below):
    // such a pipeline has nothing that could ever change its decision after creation.
    private Set<UUID> changedFacts(ItemCreateEntry e) {
        Set<UUID> facts = new HashSet<>(e.properties().keySet());
        facts.addAll(e.initialStates().keySet());
        facts.add(WatchDerivation.ITEM_CREATED);
        return facts;
    }

    private Set<UUID> changedFacts(ItemUpdateEntry e) {
        Set<UUID> facts = new HashSet<>(e.properties().keySet());
        facts.addAll(e.stateChanges().keySet());
        facts.addAll(e.stateMachinesEnded());
        return facts;
    }

    private ItemCreateEntry enrichCreate(ItemCreateEntry e) {
        Set<UUID> changedFacts = changedFacts(e);
        if (changedFacts.isEmpty()) return e;
        List<Pipeline> pipelines = pipelineSource.pipelinesWatching(e.itemTypeId(), changedFacts);
        if (pipelines.isEmpty()) return e;

        // A brand-new item carries no markers yet and has no ledger history to replay -- every
        // pipeline decision here can only ever be an add.
        Set<MarkerAttribution> toAdd = new HashSet<>();
        for (Pipeline pipeline : pipelines) {
            for (UUID markerId : PipelineEvaluator.evaluate(pipeline, e.properties(), e.itemId(), registerPartitionManager)) {
                toAdd.add(new PipelineAppliedMarker(markerId, pipeline.id()));
            }
        }
        if (toAdd.isEmpty()) return e;
        Set<MarkerAttribution> merged = new HashSet<>(e.initialMarkers());
        merged.addAll(toAdd);
        return new ItemCreateEntry(e.itemId(), e.itemTypeId(), e.properties(), e.initialStates(), merged);
    }

    private ItemUpdateEntry enrichUpdate(ItemUpdateEntry e) {
        Set<UUID> changedFacts = changedFacts(e);
        if (changedFacts.isEmpty()) return e;

        UUID itemTypeId = registerPartitionManager.findItemTypeId(e.itemId()).orElse(null);
        if (itemTypeId == null) return e; // defensive only -- an update always targets an existing, already-typed item

        List<Pipeline> pipelines = pipelineSource.pipelinesWatching(itemTypeId, changedFacts);
        if (pipelines.isEmpty()) return e;

        Map<UUID, Object> merged = registerPartitionManager.resolveMergedPropertiesById(e.itemId(), itemTypeId, e.properties());
        Map<UUID, UUID> currentPipelineAttribution = replayCurrentPipelineAttribution(e.itemId());

        Set<MarkerAttribution> toAdd = new HashSet<>();
        Set<MarkerAttribution> toRemove = new HashSet<>();
        for (Pipeline pipeline : pipelines) {
            Set<UUID> desired = PipelineEvaluator.evaluate(pipeline, merged, e.itemId(), registerPartitionManager);
            Set<UUID> currentlyHeld = new HashSet<>();
            currentPipelineAttribution.forEach((markerId, pipelineId) -> {
                if (pipelineId.equals(pipeline.id())) currentlyHeld.add(markerId);
            });
            for (UUID markerId : desired) {
                if (!currentlyHeld.contains(markerId)) toAdd.add(new PipelineAppliedMarker(markerId, pipeline.id()));
            }
            for (UUID markerId : currentlyHeld) {
                if (!desired.contains(markerId)) toRemove.add(new PipelineAppliedMarker(markerId, pipeline.id()));
            }
        }
        if (toAdd.isEmpty() && toRemove.isEmpty()) return e;

        Set<MarkerAttribution> markersAdded = new HashSet<>(e.markersAdded());
        markersAdded.addAll(toAdd);
        Set<MarkerAttribution> markersRemoved = new HashSet<>(e.markersRemoved());
        markersRemoved.addAll(toRemove);
        return new ItemUpdateEntry(e.itemId(), e.properties(), e.stateChanges(), e.stateMachinesEnded(), markersAdded, markersRemoved);
    }

    // Replays this item's committed ledger history to learn which pipeline (if any) currently
    // accounts for each PipelineAppliedMarker it carries -- an add records the attribution, a
    // remove (by this or any other source) clears it. Markers held manually, or not held at all,
    // never appear here, which is exactly right: this service only ever touches what a pipeline
    // itself previously asserted.
    private Map<UUID, UUID> replayCurrentPipelineAttribution(UUID itemId) {
        Map<UUID, UUID> current = new HashMap<>();
        for (LedgerEntry entry : ledgerPartitionManager.readItemStream(itemId)) {
            if (entry instanceof ItemCreateEntry e) {
                e.initialMarkers().forEach(m -> putIfPipeline(current, m));
            } else if (entry instanceof ItemUpdateEntry e) {
                e.markersAdded().forEach(m -> putIfPipeline(current, m));
                e.markersRemoved().forEach(m -> current.remove(m.markerId()));
            }
        }
        return current;
    }

    private void putIfPipeline(Map<UUID, UUID> current, MarkerAttribution attribution) {
        if (attribution instanceof PipelineAppliedMarker pam) current.put(pam.markerId(), pam.pipelineId());
        else current.remove(attribution.markerId());
    }
}
