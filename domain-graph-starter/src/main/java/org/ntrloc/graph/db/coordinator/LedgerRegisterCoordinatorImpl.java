package org.ntrloc.graph.db.coordinator;

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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class LedgerRegisterCoordinatorImpl implements LedgerRegisterCoordinator {

    private final LedgerPartitionManager ledgerPartitionManager;
    private final RegisterPartitionManager registerPartitionManager;
    private final ItemDeleteCascadeExpander cascadeExpander;
    private final NoOpMutationFilter noOpMutationFilter;
    private final PipelineMarkerDecisionService pipelineMarkerDecisionService;

    public LedgerRegisterCoordinatorImpl(LedgerPartitionManager ledgerPartitionManager,
                                          RegisterPartitionManager registerPartitionManager,
                                          ItemDeleteCascadeExpander cascadeExpander,
                                          NoOpMutationFilter noOpMutationFilter,
                                          PipelineMarkerDecisionService pipelineMarkerDecisionService) {
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.registerPartitionManager = registerPartitionManager;
        this.cascadeExpander = cascadeExpander;
        this.noOpMutationFilter = noOpMutationFilter;
        this.pipelineMarkerDecisionService = pipelineMarkerDecisionService;
    }

    // REQUIRES_NEW on prepare/commit/abort, not the default REQUIRED: each must durably commit (or
    // roll back) as its own independent database transaction, never silently joining whatever
    // transaction happened to already be open in the caller. That's the actual defining property of
    // two-phase commit -- a prepared state has to survive on its own, confirmable or abortable by a
    // later, separate transaction -- and it was missing before: MutationRequestProcessor.process()
    // called prepare() then commit() from inside its own single @Transactional method, so both were
    // just joining that one outer transaction and a crash between them would have rolled back the
    // "already prepared" work right along with everything else. See the marker decision pipeline
    // design notes for the fuller discussion of why this needed fixing.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(List<LedgerEntry> entries, UUID transactionId, String actorExternalId) {
        // No-op filtering runs first, before cascade expansion and before pipeline evaluation: a
        // mutation describes a desired state, and whatever's already true needs neither a cascade
        // nor a pipeline re-run, let alone a ledger row. See NoOpMutationFilter's own comment.
        List<LedgerEntry> expanded = cascadeExpander.expand(noOpMutationFilter.filter(entries));
        // Marker decision pipelines fire here, before the ledger append below -- not in commit(),
        // after it -- so a pipeline's marker decision lands in the SAME ledger row as the property
        // change that triggered it. See the marker decision pipeline design notes for why this is
        // prepare()'s job, not commit()'s. commit() needs no changes to apply what this produces:
        // it already reads markersAdded/Removed off whatever entries it's given.
        expanded = pipelineMarkerDecisionService.enrichWithPipelineDecisions(expanded);
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(UUID transactionId) {
        registerPartitionManager.abort(transactionId);
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
