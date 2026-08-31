package org.ntrloc.graph.db.partition.ledger;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget"), Map.of(), Set.of())), txn1, null);
        ledgerPartitionManager.commit(txn1, UUID.randomUUID());

        ledgerPartitionManager.append(List.of(new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"), Map.of(), Set.of(), Set.of(), Set.of())), txn2, null);
        ledgerPartitionManager.commit(txn2, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(
                new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget"), Map.of(), Set.of()),
                new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"), Map.of(), Set.of(), Set.of(), Set.of())
        );
    }

    @Test
    void uncommittedEntriesAreNotVisible() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"), Map.of(), Set.of())), txn, null);

        assertThat(ledgerPartitionManager.readItemStream(itemId)).isEmpty();
    }

    @Test
    void abortedEntriesNeverBecomeVisibleEvenIfCommitIsCalledAfter() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"), Map.of(), Set.of())), txn, null);
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
                new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(), Map.of(), Set.of()),
                new LinkDeleteEntry(linkId)
        ), txn, null);
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

        ledgerPartitionManager.append(List.of(entry), txn, null);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readLinkStream(linkId)).containsExactly(entry);
    }

    @Test
    void itemUpdateEntry_roundTripsRuleAppliedMarkerAttributionThroughTheLedger() {
        // Proves the ledger actually preserves rule attribution, not just a bare marker id -- no
        // rule engine exists yet to produce this, but the shape (and its polymorphic Jackson
        // discriminator) has to survive the JSONB payload round trip once one does.
        UUID itemId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        RuleAppliedMarker attributed = new RuleAppliedMarker(markerId, ruleId, 3);

        ledgerPartitionManager.append(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())), txn, null);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(
                new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())
        );
    }

    @Test
    void itemUpdateEntry_roundTripsManuallyAppliedMarkerAttributionThroughTheLedger() {
        // Same proof for the other MarkerAttribution variant -- both have to survive the same
        // polymorphic discriminator, not just whichever one happens to be exercised elsewhere.
        UUID itemId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        ManuallyAppliedMarker attributed = new ManuallyAppliedMarker(markerId, "some-user", "flagged by pre-production naming rule");

        ledgerPartitionManager.append(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())), txn, null);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(
                new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())
        );
    }

    @Test
    void itemDeleteEntryCarriesNoProperties() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerPartitionManager.append(List.of(new ItemDeleteEntry(itemId)), txn, null);
        ledgerPartitionManager.commit(txn, UUID.randomUUID());

        assertThat(ledgerPartitionManager.readItemStream(itemId)).containsExactly(new ItemDeleteEntry(itemId));
    }
}
