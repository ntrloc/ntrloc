package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers ManualMarkerAssignmentService's ledger+register write path in isolation from any
// permission enforcement (that's PermissionServiceInstanceReadIntegrationTest) -- just: does
// adding/removing a marker actually write a ledger_entry and land in register_item_marker. Items
// only -- markers never apply to links (see docs/ntrloc-marker-admin-ui-design-notes.md,
// "Decision: markers apply to items only").
class ManualMarkerAssignmentServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SecurityRepository securityRepo;

    private NtrlocPrincipal testActor() {
        var user = securityRepo.createUser("Marker Test Actor", "mast-actor-" + UUID.randomUUID() + "@example.com", true);
        return TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
    }

    private UUID createItem(NtrlocPrincipal actor) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createMarker() {
        return authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    @Test
    void addItemMarker_writesLedgerEntryAndPostsToRegister() {
        NtrlocPrincipal actor = testActor();
        UUID itemId = createItem(actor);
        UUID markerId = createMarker();

        markerAssignmentService.addItemMarker(itemId, markerId, actor, "flagged for review");

        assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);

        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE' AND state = 'COMMITTED'
                """)
                .param("itemId", itemId).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);

        UUID createdBy = jdbcClient.sql("""
                SELECT created_by FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE'
                """)
                .param("itemId", itemId).query(UUID.class).single();
        assertThat(createdBy).isEqualTo(actor.id());

        // ManuallyAppliedMarker's userId/reason are separate from the entry-level actor --
        // this proves both actually made it into the payload, not just the entry-level attribution.
        Long attributedRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE' AND state = 'COMMITTED'
                  AND EXISTS (
                      SELECT 1 FROM jsonb_array_elements(payload::jsonb -> 'markersAdded') marker
                      WHERE marker ->> 'markerId' = :markerId
                        AND marker ->> 'userId' = :actorUserId
                        AND marker ->> 'reason' = 'flagged for review'
                  )
                """)
                .param("itemId", itemId).param("markerId", markerId.toString())
                .param("actorUserId", actor.id().toString()).query(Long.class).single();
        assertThat(attributedRows).isEqualTo(1L);
    }

    @Test
    void addItemMarker_calledTwice_isIdempotentInTheRegister() {
        NtrlocPrincipal actor = testActor();
        UUID itemId = createItem(actor);
        UUID markerId = createMarker();

        markerAssignmentService.addItemMarker(itemId, markerId, actor, "test reason");
        markerAssignmentService.addItemMarker(itemId, markerId, actor, "test reason");

        Long registerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM register_item_marker rim
                JOIN register_item ri ON ri.id = rim.register_item_id
                WHERE ri.item_id = :itemId AND rim.marker_id = :markerId
                """)
                .param("itemId", itemId).param("markerId", markerId).query(Long.class).single();
        assertThat(registerRows).isEqualTo(1L);
    }

    @Test
    void removeItemMarker_removesFromRegisterAndWritesLedgerEntry() {
        NtrlocPrincipal actor = testActor();
        UUID itemId = createItem(actor);
        UUID markerId = createMarker();
        markerAssignmentService.addItemMarker(itemId, markerId, actor, "test reason");

        markerAssignmentService.removeItemMarker(itemId, markerId, actor, "test reason");

        assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(markerId);

        // Both add and remove write an ITEM_UPDATE entry now (markers are a facet, not their own
        // entry type) -- entry_type alone can't distinguish them, so this checks the payload's
        // markersRemoved facet specifically for this marker. markersRemoved is an array of
        // MarkerAttribution objects (not bare marker-id strings), so this has to look inside each
        // element rather than use jsonb's top-level string-array `?` existence operator.
        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE' AND state = 'COMMITTED'
                  AND EXISTS (
                      SELECT 1 FROM jsonb_array_elements(payload::jsonb -> 'markersRemoved') marker
                      WHERE marker ->> 'markerId' = :markerId
                  )
                """)
                .param("itemId", itemId).param("markerId", markerId.toString()).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);
    }

    @Test
    void itemMarker_survivesAnOrdinaryPropertyUpdateAfterward() {
        // Regression guard: register_item_marker FKs to register_item.id (ON DELETE CASCADE), and
        // an update swaps that row out for a new one wholesale (see RegisterPartitionManager.
        // commitItem). Without explicitly carrying register_item_marker rows forward to the new id
        // before deleting the old one, this marker would be silently wiped by the cascade.
        NtrlocPrincipal actor = testActor();
        UUID itemId = createItem(actor);
        UUID markerId = createMarker();
        markerAssignmentService.addItemMarker(itemId, markerId, actor, "test reason");

        UUID updateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(fixture.namePropertyId(), "Widget Pro"), Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(updateTxn, UUID.randomUUID());

        assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);
    }
}
