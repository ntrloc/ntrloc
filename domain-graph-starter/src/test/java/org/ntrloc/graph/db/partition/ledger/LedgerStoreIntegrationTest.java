package org.ntrloc.graph.db.partition.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerStore ledgerStore;

    @Autowired
    private SecurityRepository securityRepo;

    // ledger_entry.created_by (Part E) is a NOT NULL FK to security_user.id -- every append call
    // below needs a real, backed actor.
    private UUID actor;

    @BeforeEach
    void setUpActor() {
        actor = securityRepo.createUser("Ledger Test Actor", "lpm-actor-" + UUID.randomUUID() + "@example.com", true).id();
    }

    @Test
    void committedItemEntriesAreVisibleInAppendOrder() {
        UUID itemId = UUID.randomUUID();
        UUID itemTypeId = UUID.randomUUID();
        UUID namePropertyId = UUID.randomUUID();
        UUID txn1 = UUID.randomUUID();
        UUID txn2 = UUID.randomUUID();

        ledgerStore.append(List.of(new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget"), Map.of(), Set.of())), txn1, actor, OffsetDateTime.now());
        ledgerStore.commit(txn1, UUID.randomUUID());

        ledgerStore.append(List.of(new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"), Map.of(), Set.of(), Set.of(), Set.of())), txn2, actor, OffsetDateTime.now());
        ledgerStore.commit(txn2, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).containsExactly(
                new ItemCreateEntry(itemId, itemTypeId, Map.of(namePropertyId, "Widget"), Map.of(), Set.of()),
                new ItemUpdateEntry(itemId, Map.of(namePropertyId, "Widget Pro"), Map.of(), Set.of(), Set.of(), Set.of())
        );
    }

    @Test
    void uncommittedEntriesAreNotVisible() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerStore.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"), Map.of(), Set.of())), txn, actor, OffsetDateTime.now());

        assertThat(ledgerStore.readItemStream(itemId)).isEmpty();
    }

    @Test
    void abortedEntriesNeverBecomeVisibleEvenIfCommitIsCalledAfter() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerStore.append(List.of(new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(UUID.randomUUID(), "Ghost"), Map.of(), Set.of())), txn, actor, OffsetDateTime.now());
        ledgerStore.abort(txn);
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).isEmpty();
    }

    @Test
    void itemAndLinkStreamsAreIsolatedByTargetId() {
        UUID itemId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerStore.append(List.of(
                new ItemCreateEntry(itemId, UUID.randomUUID(), Map.of(), Map.of(), Set.of()),
                new LinkDeleteEntry(linkId)
        ), txn, actor, OffsetDateTime.now());
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).hasSize(1);
        assertThat(ledgerStore.readLinkStream(linkId)).hasSize(1);
        assertThat(ledgerStore.readItemStream(linkId)).isEmpty();
    }

    @Test
    void linkCreateEntryRoundTripsEndpointsAndProperties() {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        LinkCreateEntry entry = new LinkCreateEntry(linkId, UUID.randomUUID(),
                new LinkEndpoint(UUID.randomUUID(), UUID.randomUUID()),
                new LinkEndpoint(UUID.randomUUID(), UUID.randomUUID()),
                Map.of(UUID.randomUUID(), "2026"));

        ledgerStore.append(List.of(entry), txn, actor, OffsetDateTime.now());
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readLinkStream(linkId)).containsExactly(entry);
    }

    @Test
    void itemUpdateEntry_roundTripsManuallyAppliedMarkerAttributionThroughTheLedger() {
        // Same proof for the other MarkerAttribution variant -- both have to survive the same
        // polymorphic discriminator, not just whichever one happens to be exercised elsewhere.
        UUID itemId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        ManuallyAppliedMarker attributed = new ManuallyAppliedMarker(markerId, actor, "flagged by pre-production naming rule");

        ledgerStore.append(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())), txn, actor, OffsetDateTime.now());
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).containsExactly(
                new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())
        );
    }

    @Test
    void itemUpdateEntry_roundTripsPipelineAppliedMarkerAttributionThroughTheLedger() {
        // Same proof for the marker decision pipeline's own MarkerAttribution variant.
        UUID itemId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        PipelineAppliedMarker attributed = new PipelineAppliedMarker(markerId, pipelineId);

        ledgerStore.append(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())), txn, actor, OffsetDateTime.now());
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).containsExactly(
                new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(attributed), Set.of())
        );
    }

    @Test
    void itemDeleteEntryCarriesNoProperties() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        ledgerStore.append(List.of(new ItemDeleteEntry(itemId)), txn, actor, OffsetDateTime.now());
        ledgerStore.commit(txn, UUID.randomUUID());

        assertThat(ledgerStore.readItemStream(itemId)).containsExactly(new ItemDeleteEntry(itemId));
    }
}
