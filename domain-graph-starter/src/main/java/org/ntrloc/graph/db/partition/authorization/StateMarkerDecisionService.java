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

// State-entry marker decisions -- the state-machine counterpart to MarkerRuleEvaluationService.
// Each NORMAL state may carry an entryMarkerDecisionKey (a DMN key, exactly like entry_process_id
// is a BPMN key). On entering that state, the decision is evaluated once against the item's
// current property values and every marker it names is applied with StateAppliedMarker provenance;
// on leaving the state (a transition to another state, or into END), those markers are revoked
// unless the *new* state's decision also wants them, or another provenance (manual, an item-type
// rule, a state in a different machine) independently holds them.
//
// Runs in LedgerRegisterCoordinatorImpl.prepare(), right AFTER MarkerRuleEvaluationService, on the
// same ItemUpdateEntry -- so a state transition's marker consequences land in the same ledger row
// as the transition itself. Point-in-time at entry only: no standing re-evaluation while the item
// sits in the state (a later property change won't re-run the entry decision).
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

        Map<String, Object> propsByName = registerPartitionManager.resolveMergedPropertiesByName(e.itemId(), itemTypeId, e.properties());
        Map<UUID, MarkerAttribution> attribution = support.replayCurrentAttribution(e.itemId());
        Map<UUID, UUID> priorStateIds = registerPartitionManager.currentStateIds(e.itemId());

        // markerId of everything an earlier pass (rules) already put on this entry -- a state
        // decision must not add a second attribution for the same marker, and a state exit must not
        // remove a marker a rule just re-asserted (Part D).
        Set<UUID> alreadyAddedMarkerIds = e.markersAdded().stream().map(MarkerAttribution::markerId).collect(java.util.stream.Collectors.toSet());

        Set<MarkerAttribution> toAdd = new LinkedHashSet<>();
        Set<MarkerAttribution> toRemove = new LinkedHashSet<>();

        Set<UUID> affectedMachines = new LinkedHashSet<>(e.stateChanges().keySet());
        affectedMachines.addAll(e.stateMachinesEnded());

        for (UUID smId : affectedMachines) {
            UUID oldStateId = priorStateIds.get(smId);          // null for a startStateMachine
            UUID newStateId = e.stateChanges().get(smId);       // null when smId is ending (into END)

            Set<UUID> desiredIds = new HashSet<>();
            if (newStateId != null) {
                AdminStateView newState = findState(itemTypeId, smId, newStateId);
                if (newState != null && "NORMAL".equals(newState.kind())
                        && newState.entryMarkerDecisionKey() != null && !newState.entryMarkerDecisionKey().isBlank()) {
                    desiredIds.addAll(support.evaluateDecisionToMarkerIds(newState.entryMarkerDecisionKey(), itemTypeId, propsByName));
                }
            }

            // Exit reconciliation: drop every marker this machine conferred via the state being
            // left, unless the entered state wants it too, or a rule already re-asserted it here.
            if (oldStateId != null) {
                final UUID leftState = oldStateId;
                attribution.forEach((markerId, attr) -> {
                    if (attr instanceof StateAppliedMarker sam
                            && sam.stateMachineId().equals(smId) && sam.stateId().equals(leftState)
                            && !desiredIds.contains(markerId)
                            && !alreadyAddedMarkerIds.contains(markerId)) {
                        toRemove.add(sam);
                    }
                });
            }

            for (UUID markerId : desiredIds) {
                if (alreadyAddedMarkerIds.contains(markerId)) continue; // a rule already owns this marker's slot on this entry
                MarkerAttribution current = attribution.get(markerId);
                if (current != null && !(current instanceof StateAppliedMarker)) {
                    continue; // held independently (a rule or a manual application) -- not this state's to claim
                }
                if (current instanceof StateAppliedMarker sam
                        && sam.stateMachineId().equals(smId) && sam.stateId().equals(newStateId)) {
                    continue; // already attributed to this exact state (re-entry / idempotent re-run)
                }
                toAdd.add(new StateAppliedMarker(markerId, smId, newStateId));
            }
        }

        if (toAdd.isEmpty() && toRemove.isEmpty()) return entry;

        Set<MarkerAttribution> markersAdded = new LinkedHashSet<>(e.markersAdded());
        markersAdded.addAll(toAdd);
        Set<MarkerAttribution> markersRemoved = new LinkedHashSet<>(e.markersRemoved());
        markersRemoved.addAll(toRemove);
        return new ItemUpdateEntry(e.itemId(), e.properties(), e.stateChanges(), e.stateMachinesEnded(), markersAdded, markersRemoved);
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
