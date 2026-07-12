package org.ntrloc.graph.db.partition.ledger;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPartitionManagerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerPartitionManager ledgerPartitionManager;

    @Test
    void committedItemEntriesAreVisibleInAppendOrder() {
        UUID itemId = UUID.randomUUID();
        UUID itemTypeId = UUID.randomUUID();
        UUID namePropertyId = UUID.randomUUID();
        UUID txn1 = UUID.randomUUID();
        UUID txn2 = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget"))), txn1);
        ledgerPartitionManager.commit(txn1, UUID.randomUUID());

        ledgerPartitionManager.append(List.of(new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"))), txn2);
        ledgerPartitionManager.commit(txn2, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(
                new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget")),
                new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"))
        );
    }

    @Test
    void uncommittedEntriesAreNotVisible() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"))), txn);

        assertThat(ledgerPartitionManager.readItemStream(itemId)).isEmpty();
    }

    @Test
    void abortedEntriesNeverBecomeVisibleEvenIfCommitIsCalledAfter() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"))), txn);
        ledgerPartitionManager.abort(txn);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).isEmpty();
    }

    @Test
    void itemAndLinkStreamsAreIsolatedByTargetId() {
        UUID itemId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(
                new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of()),
                new LinkDeleteEntry(linkId)
        ), txn);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).hasSize(1);
        assertThat(ledgerPartitionManager.readLinkStream(linkId)).hasSize(1);
        assertThat(ledgerPartitionManager.readItemStream(linkId)).isEmpty();
    }

    @Test
    void linkCreateEntryRoundTripsEndpointsAndProperties() {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        LinkCreateEntry entry = new LinkCreateEntry(linkId, UUID.randomUUID(),
                new LinkEndpoint(UUID.randomUUID(), UUID.randomUUID()),
                new LinkEndpoint(UUID.randomUUID(), UUID.randomUUID()),
                Map.of(UUID.randomUUID(), "2026"));

        ledgerPartitionManager.append(List.of(entry), txn);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readLinkStream(linkId)).containsExactly(entry);
    }

    @Test
    void itemDeleteEntryCarriesNoProperties() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemDeleteEntry(itemId)), txn);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(new ItemDeleteEntry(itemId));
    }
}
