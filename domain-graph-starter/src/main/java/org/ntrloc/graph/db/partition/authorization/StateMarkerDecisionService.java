package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.MarkerAttribution;
import org.ntrloc.graph.db.partition.ledger.StateAppliedMarker;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// State-entry marker decisions -- the state-machine counterpart to MarkerRuleEvaluationService.
// A NORMAL state's entryMarkerDecisionKey (DMN, like entry_process_id is BPMN) runs once on entry
// with StateAppliedMarker provenance; on exit those markers are revoked unless the new state or
// another provenance (manual, a rule, a different machine) still wants them.
//
// Runs in LedgerRegisterCoordinatorImpl.prepare() right after MarkerRuleEvaluationService, on the
// same ItemUpdateEntry. Point-in-time at entry only -- no re-evaluation while the item sits in the
// state.
@Component
public class StateMarkerDecisionService {

    private final SchemaManager schemaManager;
    private final RegisterPartitionManager registerPartitionManager;
    private final MarkerDecisionSupport support;

    public StateMarkerDecisionService(SchemaManager schemaManager, RegisterPartitionManager registerPartitionManager,
                                      MarkerDecisionSupport support) {
        this.schemaManager = schemaManager;
        this.registerPartitionManager = registerPartitionManager;
        this.support = support;
    }

    /** Pure: returns entries with markersAdded/markersRemoved enriched for every state actually entered or left. Entries with no state movement pass through unchanged. */
    public List<LedgerEntry> enrichWithStateMarkerDecisions(List<LedgerEntry> entries) {
        return entries.stream().map(this::enrichEntry).toList();
    }

    private LedgerEntry enrichEntry(LedgerEntry entry) {
        if (!(entry instanceof ItemUpdateEntry e)) return entry;
        if (e.stateChanges().isEmpty() && e.stateMachinesEnded().isEmpty()) return entry;

        UUID itemTypeId = registerPartitionManager.findItemTypeId(e.itemId()).orElse(null);
        if (itemTypeId == null) return entry; // defensive -- a state transition always targets an existing item

        StateMarkerContext ctx = buildContext(e, itemTypeId);
        for (UUID smId : ctx.affectedMachines()) {
            processMachine(itemTypeId, smId, ctx);
        }

        if (ctx.toAdd().isEmpty() && ctx.toRemove().isEmpty()) return entry;

        Set<MarkerAttribution> markersAdded = new LinkedHashSet<>(e.markersAdded());
        markersAdded.addAll(ctx.toAdd());
        Set<MarkerAttribution> markersRemoved = new LinkedHashSet<>(e.markersRemoved());
        markersRemoved.addAll(ctx.toRemove());
        return new ItemUpdateEntry(e.itemId(), e.properties(), e.stateChanges(), e.stateMachinesEnded(), markersAdded, markersRemoved);
    }

    private record StateMarkerContext(
            ItemUpdateEntry entry,
            Map<String, Object> propsByName,
            Map<UUID, MarkerAttribution> attribution,
            Map<UUID, UUID> priorStateIds,
            Set<UUID> alreadyAddedMarkerIds,
            Set<UUID> affectedMachines,
            Set<MarkerAttribution> toAdd,
            Set<MarkerAttribution> toRemove) {}

    private StateMarkerContext buildContext(ItemUpdateEntry e, UUID itemTypeId) {
        Map<String, Object> propsByName = registerPartitionManager.resolveMergedPropertiesByName(e.itemId(), itemTypeId, e.properties());
        Map<UUID, MarkerAttribution> attribution = support.replayCurrentAttribution(e.itemId());
        Map<UUID, UUID> priorStateIds = registerPartitionManager.currentStateIds(e.itemId());
        // markerId of everything an earlier pass (rules) already put on this entry -- a state
        // decision must not add a second attribution for the same marker, and a state exit must not
        // remove a marker a rule just re-asserted (Part D).
        Set<UUID> alreadyAddedMarkerIds = e.markersAdded().stream().map(MarkerAttribution::markerId).collect(Collectors.toSet());
        Set<UUID> affectedMachines = new LinkedHashSet<>(e.stateChanges().keySet());
        affectedMachines.addAll(e.stateMachinesEnded());
        return new StateMarkerContext(e, propsByName, attribution, priorStateIds, alreadyAddedMarkerIds, affectedMachines,
                new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    private void processMachine(UUID itemTypeId, UUID smId, StateMarkerContext ctx) {
        UUID oldStateId = ctx.priorStateIds().get(smId);            // null for a startStateMachine
        UUID newStateId = ctx.entry().stateChanges().get(smId);     // null when smId is ending (into END)

        Set<UUID> desiredIds = resolveDesiredMarkerIds(itemTypeId, smId, newStateId, ctx.propsByName());

        // Exit reconciliation: drop every marker this machine conferred via the state being left,
        // unless the entered state wants it too, or a rule already re-asserted it here.
        if (oldStateId != null) {
            reconcileExit(smId, oldStateId, desiredIds, ctx);
        }
        reconcileEntry(smId, newStateId, desiredIds, ctx);
    }

    private Set<UUID> resolveDesiredMarkerIds(UUID itemTypeId, UUID smId, UUID newStateId, Map<String, Object> propsByName) {
        Set<UUID> desiredIds = new HashSet<>();
        if (newStateId == null) return desiredIds;
        AdminStateView newState = findState(itemTypeId, smId, newStateId);
        if (newState != null && "NORMAL".equals(newState.kind())
                && newState.entryMarkerDecisionKey() != null && !newState.entryMarkerDecisionKey().isBlank()) {
            desiredIds.addAll(support.evaluateDecisionToMarkerIds(newState.entryMarkerDecisionKey(), itemTypeId, propsByName));
        }
        return desiredIds;
    }

    private void reconcileExit(UUID smId, UUID leftState, Set<UUID> desiredIds, StateMarkerContext ctx) {
        ctx.attribution().forEach((markerId, attr) -> {
            if (attr instanceof StateAppliedMarker sam
                    && sam.stateMachineId().equals(smId) && sam.stateId().equals(leftState)
                    && !desiredIds.contains(markerId)
                    && !ctx.alreadyAddedMarkerIds().contains(markerId)) {
                ctx.toRemove().add(sam);
            }
        });
    }

    private void reconcileEntry(UUID smId, UUID newStateId, Set<UUID> desiredIds, StateMarkerContext ctx) {
        for (UUID markerId : desiredIds) {
            if (shouldClaimMarker(markerId, smId, newStateId, ctx)) {
                ctx.toAdd().add(new StateAppliedMarker(markerId, smId, newStateId));
            }
        }
    }

    private boolean shouldClaimMarker(UUID markerId, UUID smId, UUID newStateId, StateMarkerContext ctx) {
        if (ctx.alreadyAddedMarkerIds().contains(markerId)) {
            return false; // a rule already owns this marker's slot on this entry
        }
        MarkerAttribution current = ctx.attribution().get(markerId);
        if (current != null && !(current instanceof StateAppliedMarker)) {
            return false; // held independently (a rule or a manual application) -- not this state's to claim
        }
        // not already attributed to this exact state (re-entry / idempotent re-run)
        return !(current instanceof StateAppliedMarker sam
                && sam.stateMachineId().equals(smId) && sam.stateId().equals(newStateId));
    }

    private AdminStateView findState(UUID itemTypeId, UUID smId, UUID stateId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemTypeId)).findFirst()
                .map(AdminItemDefinitionView::stateMachines).orElse(List.of()).stream()
                .filter(m -> m.id().equals(smId)).findFirst()
                .map(AdminStateMachineView::states).orElse(List.of()).stream()
                .filter(s -> s.id().equals(stateId)).findFirst()
                .orElse(null);
    }
}
