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
        for (LedgerEntry entry : expanded) {
            if (entry instanceof ItemCreateEntry e) {
                registerPartitionManager.stageItemCreate(e.itemId(), e.itemTypeId(), e.properties(), transactionId);
            } else if (entry instanceof ItemUpdateEntry e && !e.properties().isEmpty()) {
                registerPartitionManager.stageItemUpdate(e.itemId(), e.properties(), transactionId);
            }
        }
        for (LedgerEntry entry : expanded) {
            if (entry instanceof LinkCreateEntry e) {
                registerPartitionManager.stageLinkCreate(e.linkId(), e.linkTypeId(),
                        toRegisterEndpoint(e.endpointA()), toRegisterEndpoint(e.endpointB()), e.properties(), transactionId);
            } else if (entry instanceof LinkUpdateEntry e) {
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
            } else if (entry instanceof ItemUpdateEntry e && !e.properties().isEmpty()) {
                registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
            }
        }
        for (LedgerEntry entry : entries) {
            if (entry instanceof LinkCreateEntry e) {
                registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
            } else if (entry instanceof LinkUpdateEntry e) {
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
