package org.ntrloc.graph.db.partition.authorization;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.api.DmnRepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerPartitionManager;
import org.ntrloc.graph.db.partition.ledger.MarkerAttribution;
import org.ntrloc.graph.db.partition.ledger.RuleAppliedMarker;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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

    private static final Logger log = LoggerFactory.getLogger(MarkerRuleEvaluationService.class);

    private final AuthorizationRepository authRepo;
    private final RegisterPartitionManager registerPartitionManager;
    private final LedgerPartitionManager ledgerPartitionManager;
    private final DmnDecisionService dmnDecisionService;
    private final DmnRepositoryService dmnRepositoryService;

    public MarkerRuleEvaluationService(AuthorizationRepository authRepo, RegisterPartitionManager registerPartitionManager,
                                        LedgerPartitionManager ledgerPartitionManager, DmnDecisionService dmnDecisionService,
                                        DmnRepositoryService dmnRepositoryService) {
        this.authRepo = authRepo;
        this.registerPartitionManager = registerPartitionManager;
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.dmnDecisionService = dmnDecisionService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    /** Pure: returns entries with markersAdded/markersRemoved enriched wherever a bound rule fired. Entries with no property change, or no rule bound to their item type, pass through unchanged. */
    public List<LedgerEntry> enrichWithRuleDecisions(List<LedgerEntry> entries) {
        return entries.stream().map(this::enrichEntry).toList();
    }

    private LedgerEntry enrichEntry(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry e && !e.properties().isEmpty()) {
            return enrichCreate(e);
        } else if (entry instanceof ItemUpdateEntry e && !e.properties().isEmpty()) {
            return enrichUpdate(e);
        }
        return entry;
    }

    private ItemCreateEntry enrichCreate(ItemCreateEntry e) {
        List<AuthorizationRepository.MarkerRuleRow> rules = authRepo.getEnabledMarkerRulesForItemType(e.itemTypeId());
        if (rules.isEmpty()) return e;

        Map<String, Object> propertiesByName = registerPartitionManager.resolvePropertiesByName(e.itemTypeId(), e.properties());
        // A brand-new item carries no markers yet and has no ledger history to replay -- every
        // rule decision here can only ever be an add.
        Set<MarkerAttribution> toAdd = new HashSet<>();
        for (var rule : rules) {
            for (UUID markerId : desiredMarkerIds(rule, e.itemTypeId(), propertiesByName)) {
                toAdd.add(new RuleAppliedMarker(markerId, rule.id(), decisionVersion(rule.decisionKey())));
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

        Map<String, Object> propertiesByName = registerPartitionManager.resolveMergedPropertiesByName(e.itemId(), itemTypeId, e.properties());
        Set<UUID> currentlyApplied = authRepo.getMarkerIdsForItem(e.itemId());
        Map<UUID, MarkerAttribution> currentAttributionByMarker = replayCurrentAttribution(e.itemId());

        Set<MarkerAttribution> toAdd = new HashSet<>();
        Set<MarkerAttribution> toRemove = new HashSet<>();
        for (var rule : rules) {
            Set<UUID> desired = desiredMarkerIds(rule, itemTypeId, propertiesByName);
            int ruleVersion = decisionVersion(rule.decisionKey());

            for (UUID markerId : desired) {
                if (!currentlyApplied.contains(markerId)) {
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
        return new ItemUpdateEntry(e.itemId(), e.properties(), e.stateChanges(), markersAdded, markersRemoved);
    }

    private Set<UUID> desiredMarkerIds(AuthorizationRepository.MarkerRuleRow rule, UUID itemTypeId, Map<String, Object> propertiesByName) {
        List<Map<String, Object>> outputRows;
        try {
            outputRows = dmnDecisionService.createExecuteDecisionBuilder()
                    .decisionKey(rule.decisionKey())
                    .variables(propertiesByName)
                    .execute();
        } catch (FlowableObjectNotFoundException e) {
            // A rule row can exist (created from the Schema editor's own "+ New assignment rule")
            // before its decision table is ever deployed under that key -- see
            // AuthorizationRepository.createMarkerRule's own comment on why that's an intentionally
            // safe, valid intermediate state rather than something rule creation should block on.
            // Treated as "this rule has nothing to say yet," same as decisionVersion()'s own
            // null-decision handling below, not as a failure that should block the mutation this
            // evaluation is running inside of.
            log.warn("Marker rule '{}' ({}) references decision key '{}', which has no deployed decision table -- skipping", rule.name(), rule.id(), rule.decisionKey());
            return Set.of();
        }
        Set<UUID> markerIds = new HashSet<>();
        for (Map<String, Object> row : outputRows) {
            Object markerName = row.get("markerName");
            if (markerName == null) continue;
            authRepo.findItemTypeScopedMarkerByName(itemTypeId, markerName.toString()).ifPresent(markerIds::add);
        }
        return markerIds;
    }

    private int decisionVersion(String decisionKey) {
        DmnDecision decision = dmnRepositoryService.createDecisionQuery()
                .decisionKey(decisionKey)
                .latestVersion()
                .singleResult();
        return decision != null ? decision.getVersion() : 0;
    }

    // Reconstructs "which rule (if any) currently accounts for each of this item's applied
    // markers" by replaying its committed ledger history in order -- an add sets the marker's
    // attribution, a remove clears it. This is the mechanism, not a new ownership column: the
    // register only ever stores whether a marker is currently applied, never why (see
    // RegisterPartitionManager.postItemMarkerAdd's own comment), so provenance has to come from
    // the ledger, the one place MarkerAttribution actually persists.
    private Map<UUID, MarkerAttribution> replayCurrentAttribution(UUID itemId) {
        Map<UUID, MarkerAttribution> current = new HashMap<>();
        for (LedgerEntry entry : ledgerPartitionManager.readItemStream(itemId)) {
            if (entry instanceof ItemCreateEntry e) {
                e.initialMarkers().forEach(m -> current.put(m.markerId(), m));
            } else if (entry instanceof ItemUpdateEntry e) {
                e.markersAdded().forEach(m -> current.put(m.markerId(), m));
                e.markersRemoved().forEach(m -> current.remove(m.markerId()));
            }
        }
        return current;
    }
}
