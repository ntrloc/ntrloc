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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class LedgerRegisterCoordinatorImpl implements LedgerRegisterCoordinator {

    private final LedgerPartitionManager ledgerPartitionManager;
    private final RegisterPartitionManager registerPartitionManager;
    private final ItemDeleteCascadeExpander cascadeExpander;

    public LedgerRegisterCoordinatorImpl(LedgerPartitionManager ledgerPartitionManager,
                                          RegisterPartitionManager registerPartitionManager,
                                          ItemDeleteCascadeExpander cascadeExpander) {
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.registerPartitionManager = registerPartitionManager;
        this.cascadeExpander = cascadeExpander;
    }

    @Override
    @Transactional
    public void prepare(List<LedgerEntry> entries, UUID transactionId, String actorExternalId) {
        List<LedgerEntry> expanded = cascadeExpander.expand(entries);
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
            } else if (entry instanceof ItemUpdateEntry e && (!e.properties().isEmpty() || !e.stateChanges().isEmpty())) {
                registerPartitionManager.stageItemChange(e.itemId(), e.properties(), e.stateChanges(), transactionId);
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

        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemCreateEntry e) {
                registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
            } else if (entry instanceof ItemUpdateEntry e && (!e.properties().isEmpty() || !e.stateChanges().isEmpty())) {
                registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
            }
        }
        for (LedgerEntry entry : entries) {
            if (entry instanceof LinkCreateEntry e) {
                registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
            } else if (entry instanceof LinkUpdateEntry e && !e.properties().isEmpty()) {
                registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
            }
        }
        // Link deletes before item deletes: a perspective row's FK to register_item has no
        // cascade, so a still-linked item can't be deleted until its links are gone first.
        for (LedgerEntry entry : entries) {
            if (entry instanceof LinkDeleteEntry e) registerPartitionManager.deleteLink(e.linkId());
        }
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemDeleteEntry e) registerPartitionManager.deleteItem(e.itemId());
        }
        // Markers apply last, once the row they attach to is guaranteed to exist in its final
        // committed form (a fresh create's row, or an update's swapped-in row). Only markerId
        // crosses into the register -- ruleId/ruleVersion/reason are attribution, a ledger-only
        // concern (the register only ever needs "is this marker currently applied," never why).
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemCreateEntry e) {
                e.initialMarkers().forEach(m -> registerPartitionManager.postItemMarkerAdd(e.itemId(), m.markerId()));
            } else if (entry instanceof ItemUpdateEntry e) {
                e.markersAdded().forEach(m -> registerPartitionManager.postItemMarkerAdd(e.itemId(), m.markerId()));
                e.markersRemoved().forEach(m -> registerPartitionManager.postItemMarkerRemove(e.itemId(), m.markerId()));
            } else if (entry instanceof LinkCreateEntry e) {
                e.initialMarkers().forEach(m -> registerPartitionManager.postLinkMarkerAdd(e.linkId(), m.markerId()));
            } else if (entry instanceof LinkUpdateEntry e) {
                e.markersAdded().forEach(m -> registerPartitionManager.postLinkMarkerAdd(e.linkId(), m.markerId()));
                e.markersRemoved().forEach(m -> registerPartitionManager.postLinkMarkerRemove(e.linkId(), m.markerId()));
            }
        }

        ledgerPartitionManager.commit(transactionId, commitId);
    }

    @Override
    @Transactional
    public void abort(UUID transactionId) {
        registerPartitionManager.discardStaged(transactionId);
        ledgerPartitionManager.abort(transactionId);
    }

    private RegisterLinkEndpoint toRegisterEndpoint(LinkEndpoint endpoint) {
        return new RegisterLinkEndpoint(endpoint.perspectiveId(), endpoint.itemId());
    }
}
