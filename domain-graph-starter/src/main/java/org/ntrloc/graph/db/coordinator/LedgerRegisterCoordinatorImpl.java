package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.authorization.MarkerRuleEvaluationService;
import org.ntrloc.graph.db.partition.authorization.StateMarkerDecisionService;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerPartitionManager;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterLinkEndpoint;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class LedgerRegisterCoordinatorImpl implements LedgerRegisterCoordinator {

    private final LedgerPartitionManager ledgerPartitionManager;
    private final RegisterPartitionManager registerPartitionManager;
    private final ItemDeleteCascadeExpander cascadeExpander;
    private final MarkerRuleEvaluationService markerRuleEvaluationService;
    private final StateMarkerDecisionService stateMarkerDecisionService;

    // @Lazy breaks a real circular bean dependency, same technique and same reason as
    // AuthorizationRepository's own @Lazy AuthorizationCacheManager: MarkerRuleEvaluationService
    // needs Flowable's DmnDecisionService/DmnRepositoryService, which come from the DMN engine
    // config, which is built on top of the process engine config, which (via EntityManagerImpl ->
    // MutationRequestProcessor) depends right back on this coordinator. A deferred-resolution
    // proxy here means this bean's own construction doesn't require that whole chain to already
    // exist -- it's only resolved the first time prepare() actually calls it.
    public LedgerRegisterCoordinatorImpl(LedgerPartitionManager ledgerPartitionManager,
                                          RegisterPartitionManager registerPartitionManager,
                                          ItemDeleteCascadeExpander cascadeExpander,
                                          @Lazy MarkerRuleEvaluationService markerRuleEvaluationService,
                                          @Lazy StateMarkerDecisionService stateMarkerDecisionService) {
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.registerPartitionManager = registerPartitionManager;
        this.cascadeExpander = cascadeExpander;
        this.markerRuleEvaluationService = markerRuleEvaluationService;
        this.stateMarkerDecisionService = stateMarkerDecisionService;
    }

    @Override
    @Transactional
    public void prepare(List<LedgerEntry> entries, UUID transactionId, String actorExternalId) {
        List<LedgerEntry> expanded = cascadeExpander.expand(entries);
        // Marker Assignment Rules fire here, before the ledger append below -- not in commit(),
        // after it -- so a rule's marker decision lands in the SAME ledger row as the property
        // change that triggered it (see MarkerRuleEvaluationService's own comment). commit()
        // needs no changes to apply what this produces: it already reads markersAdded/Removed off
        // whatever entries it's given.
        expanded = markerRuleEvaluationService.enrichWithRuleDecisions(expanded);
        // State-entry marker decisions run second, on the same entries: a state transition's
        // add/remove of StateAppliedMarker attributions folds into the same ledger row, and running
        // after the rules lets it dedup against (and defer to) any marker a rule just re-asserted.
        expanded = stateMarkerDecisionService.enrichWithStateMarkerDecisions(expanded);
        ledgerPartitionManager.append(expanded, transactionId, actorExternalId);

        // Items before links: link endpoint resolution needs same-transaction item staging
        // to already exist so it can prefer the fresh row over the old committed one.
        //
        // Each of properties/stateChanges/markers is a facet of ONE ItemUpdateEntry now, not a
        // separate entry type, so there's exactly one entry per item to stage here -- no grouping
        // or merging needed (that used to be a real requirement when state changes were their own
        // entry type; see git history). markers never touch staging at all -- they apply directly
        // against the already-committed row at commit time (see postItemMarkerAdd's own comment),
        // so only properties/stateChanges gate whether there's a row to stage.
        for (LedgerEntry entry : expanded) {
            if (entry instanceof ItemCreateEntry e) {
                registerPartitionManager.stageItemCreate(e.itemId(), e.itemTypeId(), e.properties(), e.initialStates(), transactionId);
            } else if (entry instanceof ItemUpdateEntry e && hasRegisterEffect(e)) {
                registerPartitionManager.stageItemChange(e.itemId(), e.properties(), e.stateChanges(), e.stateMachinesEnded(), transactionId);
            }
        }
        for (LedgerEntry entry : expanded) {
            if (entry instanceof LinkCreateEntry e) {
                registerPartitionManager.stageLinkCreate(e.linkId(), e.linkTypeId(),
                        toRegisterEndpoint(e.endpointA()), toRegisterEndpoint(e.endpointB()), e.properties(), transactionId);
            } else if (entry instanceof LinkUpdateEntry e && !e.properties().isEmpty()) {
                registerPartitionManager.stageLinkUpdate(e.linkId(), e.properties(), transactionId);
            }
        }
    }

    @Override
    @Transactional
    public void commit(UUID transactionId, UUID commitId) {
        List<LedgerEntry> entries = ledgerPartitionManager.readTransaction(transactionId);

        commitItems(entries, transactionId, commitId);
        commitLinks(entries, transactionId, commitId);
        // Link deletes before item deletes: a perspective row's FK to register_item has no
        // cascade, so a still-linked item can't be deleted until its links are gone first.
        deleteLinks(entries);
        deleteItems(entries);
        // Markers apply last, once the row they attach to is guaranteed to exist in its final
        // committed form (a fresh create's row, or an update's swapped-in row). Only markerId
        // crosses into the register -- ruleId/ruleVersion/reason are attribution, a ledger-only
        // concern (the register only ever needs "is this marker currently applied," never why).
        // Items only -- markers never apply to links (see LinkCreateEntry/LinkUpdateEntry's own
        // comments).
        applyMarkers(entries);

        ledgerPartitionManager.commit(transactionId, commitId);
    }

    private void commitItems(List<LedgerEntry> entries, UUID transactionId, UUID commitId) {
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemCreateEntry e) {
                registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
            } else if (entry instanceof ItemUpdateEntry e && hasRegisterEffect(e)) {
                registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
            }
        }
    }

    private void commitLinks(List<LedgerEntry> entries, UUID transactionId, UUID commitId) {
        for (LedgerEntry entry : entries) {
            if (entry instanceof LinkCreateEntry e) {
                registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
            } else if (entry instanceof LinkUpdateEntry e && !e.properties().isEmpty()) {
                registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
            }
        }
    }

    private void deleteLinks(List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            if (entry instanceof LinkDeleteEntry e) registerPartitionManager.deleteLink(e.linkId());
        }
    }

    private void deleteItems(List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemDeleteEntry e) registerPartitionManager.deleteItem(e.itemId());
        }
    }

    private void applyMarkers(List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemCreateEntry e) {
                e.initialMarkers().forEach(m -> registerPartitionManager.postItemMarkerAdd(e.itemId(), m.markerId()));
            } else if (entry instanceof ItemUpdateEntry e) {
                e.markersAdded().forEach(m -> registerPartitionManager.postItemMarkerAdd(e.itemId(), m.markerId()));
                e.markersRemoved().forEach(m -> registerPartitionManager.postItemMarkerRemove(e.itemId(), m.markerId()));
            }
        }
    }

    @Override
    @Transactional
    public void abort(UUID transactionId) {
        registerPartitionManager.discardStaged(transactionId);
        ledgerPartitionManager.abort(transactionId);
    }

    // An ItemUpdateEntry only needs a staged/committed register row when it actually changes the
    // register: a property diff, a state change, or a state machine ending (markers apply directly
    // to the already-committed row and never gate staging).
    private static boolean hasRegisterEffect(ItemUpdateEntry e) {
        return !e.properties().isEmpty() || !e.stateChanges().isEmpty() || !e.stateMachinesEnded().isEmpty();
    }

    private RegisterLinkEndpoint toRegisterEndpoint(LinkEndpoint endpoint) {
        return new RegisterLinkEndpoint(endpoint.perspectiveId(), endpoint.itemId());
    }
}
