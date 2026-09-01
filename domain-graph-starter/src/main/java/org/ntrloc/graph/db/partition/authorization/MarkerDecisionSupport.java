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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// The two things both DMN-driven marker mechanisms need -- item-type marker rules
// (MarkerRuleEvaluationService) and state-entry marker decisions (StateMarkerDecisionService):
//   1. replay the ledger to learn which attribution currently accounts for each applied marker;
//   2. execute a DMN decision key against a bag of property values and turn the "markerName"
//      output column into resolved, item-type-scoped marker ids.
// Neither is a new source of truth: provenance still lives only in the ledger's MarkerAttribution
// payloads, and a decision key is still just free-text bound to a Flowable deployment.
@Component
public class MarkerDecisionSupport {

    private static final Logger log = LoggerFactory.getLogger(MarkerDecisionSupport.class);

    private final AuthorizationRepository authRepo;
    private final LedgerPartitionManager ledgerPartitionManager;
    private final DmnDecisionService dmnDecisionService;
    private final DmnRepositoryService dmnRepositoryService;

    public MarkerDecisionSupport(AuthorizationRepository authRepo, LedgerPartitionManager ledgerPartitionManager,
                                 DmnDecisionService dmnDecisionService, DmnRepositoryService dmnRepositoryService) {
        this.authRepo = authRepo;
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.dmnDecisionService = dmnDecisionService;
        this.dmnRepositoryService = dmnRepositoryService;
    }

    // Reconstructs "which attribution (rule, manual, or state) currently accounts for each of this
    // item's applied markers" by replaying its committed ledger history in order -- an add sets the
    // marker's attribution, a remove clears it. This is the mechanism, not a new ownership column:
    // the register only ever stores whether a marker is currently applied, never why (see
    // RegisterPartitionManager.postItemMarkerAdd's own comment), so provenance has to come from the
    // ledger, the one place MarkerAttribution actually persists.
    public Map<UUID, MarkerAttribution> replayCurrentAttribution(UUID itemId) {
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

    // Executes the decision key against propertiesByName and resolves every "markerName" output
    // cell to an ITEM_TYPE-scoped marker id for itemTypeId. Unresolved names are dropped. A key
    // with no deployed decision table yields an empty list (not an error) -- a marker rule row or a
    // state's entry_marker_decision_key can legitimately name a key that isn't deployed yet.
    //
    // A rule's "markerName" cell may be a single value or a FEEL list (["A","B"]) -- the editor's
    // checkbox-list output cell writes the latter when more than one marker is ticked for a rule --
    // so each hit row contributes 0-to-many names, not exactly one.
    public List<UUID> evaluateDecisionToMarkerIds(String decisionKey, UUID itemTypeId, Map<String, Object> propertiesByName) {
        List<Map<String, Object>> outputRows;
        try {
            outputRows = dmnDecisionService.createExecuteDecisionBuilder()
                    .decisionKey(decisionKey)
                    .variables(propertiesByName)
                    .execute();
        } catch (FlowableObjectNotFoundException e) {
            log.warn("Decision key '{}' has no deployed decision table -- treating as 'nothing to say yet'", decisionKey);
            return List.of();
        }
        List<UUID> markerIds = new ArrayList<>();
        for (Map<String, Object> row : outputRows) {
            for (String name : markerNamesFromCell(row.get("markerName"))) {
                authRepo.findItemTypeScopedMarkerByName(itemTypeId, name)
                        .filter(id -> !markerIds.contains(id))
                        .ifPresent(markerIds::add);
            }
        }
        return markerIds;
    }

    // A hit row's "markerName" cell contributes 0-to-many names. The editor's checkbox-list output
    // cell writes the ticked names as a single comma-joined string ("A,B,C") -- the DMN engine here
    // evaluates cells as JUEL, which has no list literal, so a comma-joined string is how "several
    // markers for one rule" is expressed. A genuine collection value (a hand-authored FEEL table,
    // if the engine is ever switched) is handled too.
    private static List<String> markerNamesFromCell(Object cell) {
        if (cell == null) return List.of();
        Collection<?> elements = cell instanceof Collection<?> c ? c : List.of(cell);
        List<String> names = new ArrayList<>();
        for (Object element : elements) {
            if (element == null) continue;
            for (String part : element.toString().split(",")) {
                if (!part.isBlank()) names.add(part.trim());
            }
        }
        return names;
    }

    public int decisionVersion(String decisionKey) {
        DmnDecision decision = dmnRepositoryService.createDecisionQuery()
                .decisionKey(decisionKey)
                .latestVersion()
                .singleResult();
        return decision != null ? decision.getVersion() : 0;
    }
}
