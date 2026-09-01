package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.MarkerAttribution;
import org.ntrloc.graph.db.partition.ledger.RuleAppliedMarker;
import org.ntrloc.graph.db.partition.ledger.StateAppliedMarker;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Marker Assignment Rules (tracer bullet). Evaluates any DMN-backed rule bound to an item's type
// against that item's own properties, and folds the resulting marker add/remove decision into the
// SAME ledger entry as the property change that triggered it -- called from
// LedgerRegisterCoordinatorImpl.prepare(), BEFORE the ledger append, not from commit() after it.
// This matters: ItemUpdateEntry's own design is "one entry, three optional facets," specifically
// so an admin reading the ledger sees one row showing both what changed and what it caused, not
// two rows correlated only by a shared transaction id. See docs/ntrloc-security-projections-
// summary.md's "Marker Assignment Rules" sketch and MarkerAssignmentService's own comment on why
// RuleAppliedMarker existed as an unused sealed-interface variant until now.
//
// Own-properties-only, item-only scope for this first slice -- no traversal to related items via
// link perspectives yet (that needs its own cascading-re-evaluation design, flagged as a real gap
// in the docs, not attempted here).
@Service
public class MarkerRuleEvaluationService {

    private final AuthorizationRepository authRepo;
    private final RegisterPartitionManager registerPartitionManager;
    private final MarkerDecisionSupport support;

    public MarkerRuleEvaluationService(AuthorizationRepository authRepo, RegisterPartitionManager registerPartitionManager,
                                        MarkerDecisionSupport support) {
        this.authRepo = authRepo;
        this.registerPartitionManager = registerPartitionManager;
        this.support = support;
    }

    /** Pure: returns entries with markersAdded/markersRemoved enriched wherever a bound rule fired. Entries with no property change, or no rule bound to their item type, pass through unchanged. */
    public List<LedgerEntry> enrichWithRuleDecisions(List<LedgerEntry> entries) {
        return entries.stream().map(this::enrichEntry).toList();
    }

    private LedgerEntry enrichEntry(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry e && !e.properties().isEmpty()) {
            return enrichCreate(e);
        } else if (entry instanceof ItemUpdateEntry e && triggersRuleEvaluation(e)) {
            return enrichUpdate(e);
        }
        return entry;
    }

    // A property change is the obvious trigger, but a state transition is one too: a state's exit
    // reconciliation (StateMarkerDecisionService) may drop a marker that an item-type rule still
    // wants against the item's current (unchanged) properties, so the rules re-evaluate on any
    // transition and re-assert into the SAME entry. enrichUpdate with an empty properties() diff
    // resolves to the item's current committed properties -- idempotent when nothing relevant moved.
    private boolean triggersRuleEvaluation(ItemUpdateEntry e) {
        return !e.properties().isEmpty() || !e.stateChanges().isEmpty() || !e.stateMachinesEnded().isEmpty();
    }

    private ItemCreateEntry enrichCreate(ItemCreateEntry e) {
        List<AuthorizationRepository.MarkerRuleRow> rules = authRepo.getEnabledMarkerRulesForItemType(e.itemTypeId());
        if (rules.isEmpty()) return e;

        Map<String, Object> propertiesByName = registerPartitionManager.resolvePropertiesByName(e.itemTypeId(), e.properties());
        // A brand-new item carries no markers yet and has no ledger history to replay -- every
        // rule decision here can only ever be an add.
        Set<MarkerAttribution> toAdd = new HashSet<>();
        for (var rule : rules) {
            for (UUID markerId : support.evaluateDecisionToMarkerIds(rule.decisionKey(), e.itemTypeId(), propertiesByName)) {
                toAdd.add(new RuleAppliedMarker(markerId, rule.id(), support.decisionVersion(rule.decisionKey())));
            }
        }
        if (toAdd.isEmpty()) return e;
        Set<MarkerAttribution> merged = new HashSet<>(e.initialMarkers());
        merged.addAll(toAdd);
        return new ItemCreateEntry(e.itemId(), e.itemTypeId(), e.properties(), e.initialStates(), merged);
    }

    private ItemUpdateEntry enrichUpdate(ItemUpdateEntry e) {
        UUID itemTypeId = registerPartitionManager.findItemTypeId(e.itemId()).orElse(null);
        if (itemTypeId == null) return e; // defensive only -- an update always targets an existing, already-typed item

        List<AuthorizationRepository.MarkerRuleRow> rules = authRepo.getEnabledMarkerRulesForItemType(itemTypeId);
        if (rules.isEmpty()) return e;

        // On a state transition the rules re-run against unchanged properties (see enrichEntry's
        // gate). If a rule still wants a marker that a state currently confers, the rule "adopts" it
        // -- re-asserting a RuleAppliedMarker so the same-entry state-exit reconciliation
        // (StateMarkerDecisionService) sees it as already-claimed and leaves it in place.
        boolean stateTransition = !e.stateChanges().isEmpty() || !e.stateMachinesEnded().isEmpty();

        Map<String, Object> propertiesByName = registerPartitionManager.resolveMergedPropertiesByName(e.itemId(), itemTypeId, e.properties());
        Set<UUID> currentlyApplied = authRepo.getMarkerIdsForItem(e.itemId());
        Map<UUID, MarkerAttribution> currentAttributionByMarker = support.replayCurrentAttribution(e.itemId());

        Set<MarkerAttribution> toAdd = new HashSet<>();
        Set<MarkerAttribution> toRemove = new HashSet<>();
        for (var rule : rules) {
            Set<UUID> desired = new HashSet<>(support.evaluateDecisionToMarkerIds(rule.decisionKey(), itemTypeId, propertiesByName));
            int ruleVersion = support.decisionVersion(rule.decisionKey());

            for (UUID markerId : desired) {
                boolean heldByStateOnly = currentAttributionByMarker.get(markerId) instanceof StateAppliedMarker;
                if (!currentlyApplied.contains(markerId) || (stateTransition && heldByStateOnly)) {
                    toAdd.add(new RuleAppliedMarker(markerId, rule.id(), ruleVersion));
                }
            }
            // Removable only if this exact rule is why the marker is currently applied -- a
            // marker present via manual application, or via a different rule, is left alone even
            // if this rule's current decision wouldn't have added it.
            currentAttributionByMarker.forEach((markerId, attribution) -> {
                if (attribution instanceof RuleAppliedMarker ram && ram.ruleId().equals(rule.id()) && !desired.contains(markerId)) {
                    toRemove.add(ram);
                }
            });
        }
        if (toAdd.isEmpty() && toRemove.isEmpty()) return e;

        Set<MarkerAttribution> markersAdded = new HashSet<>(e.markersAdded());
        markersAdded.addAll(toAdd);
        Set<MarkerAttribution> markersRemoved = new HashSet<>(e.markersRemoved());
        markersRemoved.addAll(toRemove);
        return new ItemUpdateEntry(e.itemId(), e.properties(), e.stateChanges(), e.stateMachinesEnded(), markersAdded, markersRemoved);
    }
}
