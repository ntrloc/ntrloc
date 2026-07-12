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
    public void prepare(List<LedgerEntry> entries, UUID transactionId) {
        List<LedgerEntry> expanded = cascadeExpander.expand(entries);
        ledgerPartitionManager.append(expanded, transactionId);

        // Items before links: link endpoint resolution needs same-transaction item staging
        // to already exist so it can prefer the fresh row over the old committed one.
        for (LedgerEntry entry : expanded) {
            switch (entry) {
                case ItemCreateEntry e -> registerPartitionManager.stageItemCreate(e.itemId(), e.itemTypeId(), e.properties(), transactionId);
                // An empty diff (the cascade's ripple entries) means nothing about the item's
                // own properties changed -- skip the register write entirely rather than
                // versioning the row (and repointing its perspectives) for no actual content.
                case ItemUpdateEntry e when !e.properties().isEmpty() -> registerPartitionManager.stageItemUpdate(e.itemId(), e.properties(), transactionId);
                default -> { }
            }
        }
        for (LedgerEntry entry : expanded) {
            switch (entry) {
                case LinkCreateEntry e -> registerPartitionManager.stageLinkCreate(e.linkId(), e.linkTypeId(), toRegisterEndpoints(e.endpoints()), e.properties(), transactionId);
                case LinkUpdateEntry e -> registerPartitionManager.stageLinkUpdate(e.linkId(), e.properties(), transactionId);
                default -> { }
            }
        }
    }

    @Override
    @Transactional
    public void commit(UUID transactionId, UUID commitId) {
        List<LedgerEntry> entries = ledgerPartitionManager.readTransaction(transactionId);

        for (LedgerEntry entry : entries) {
            switch (entry) {
                case ItemCreateEntry e -> registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
                case ItemUpdateEntry e when !e.properties().isEmpty() -> registerPartitionManager.commitItem(e.itemId(), transactionId, commitId);
                default -> { }
            }
        }
        for (LedgerEntry entry : entries) {
            switch (entry) {
                case LinkCreateEntry e -> registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
                case LinkUpdateEntry e -> registerPartitionManager.commitLink(e.linkId(), transactionId, commitId);
                default -> { }
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

    private List<RegisterLinkEndpoint> toRegisterEndpoints(List<LinkEndpoint> endpoints) {
        return endpoints.stream().map(e -> new RegisterLinkEndpoint(e.perspectiveId(), e.itemId())).toList();
    }
}
