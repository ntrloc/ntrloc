package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers MarkerAssignmentService's ledger+register write path in isolation from any permission
// enforcement (that's PermissionServiceInstanceReadIntegrationTest) -- just: does adding/removing
// a marker actually write a ledger_entry and land in register_item_marker/register_link_marker.
class MarkerAssignmentServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MarkerAssignmentService markerAssignmentService;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID createItem() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"))), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createLink(UUID productId, UUID contributorId) {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return linkId;
    }

    @Test
    void addItemMarker_writesLedgerEntryAndPostsToRegister() {
        UUID itemId = createItem();
        UUID markerId = authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker").id();

        markerAssignmentService.addItemMarker(itemId, markerId, "test-actor");

        assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);

        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_MARKER_ADD' AND state = 'COMMITTED'
                """)
                .param("itemId", itemId).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);

        String actor = jdbcClient.sql("""
                SELECT actor_external_id FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_MARKER_ADD'
                """)
                .param("itemId", itemId).query(String.class).single();
        assertThat(actor).isEqualTo("test-actor");
    }

    @Test
    void addItemMarker_calledTwice_isIdempotentInTheRegister() {
        UUID itemId = createItem();
        UUID markerId = authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker").id();

        markerAssignmentService.addItemMarker(itemId, markerId, null);
        markerAssignmentService.addItemMarker(itemId, markerId, null);

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
        UUID itemId = createItem();
        UUID markerId = authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker").id();
        markerAssignmentService.addItemMarker(itemId, markerId, null);

        markerAssignmentService.removeItemMarker(itemId, markerId, null);

        assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(markerId);

        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_MARKER_REMOVE' AND state = 'COMMITTED'
                """)
                .param("itemId", itemId).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);
    }

    @Test
    void addLinkMarker_writesLedgerEntryAndPostsToRegister() {
        UUID productId = createItem();
        UUID contributorId = UUID.randomUUID();
        UUID contributorTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(contributorId, fixture.contributorTypeId(),
                Map.of(fixture.contributorNamePropertyId(), "Ada"))), contributorTxn, null);
        coordinator.commit(contributorTxn, UUID.randomUUID());
        UUID linkId = createLink(productId, contributorId);
        UUID markerId = authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker").id();

        markerAssignmentService.addLinkMarker(linkId, markerId, null);

        assertThat(authRepo.getMarkerIdsForLink(linkId)).contains(markerId);

        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'LINK' AND target_id = :linkId AND entry_type = 'LINK_MARKER_ADD' AND state = 'COMMITTED'
                """)
                .param("linkId", linkId).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);
    }

    @Test
    void removeLinkMarker_removesFromRegister() {
        UUID productId = createItem();
        UUID contributorId = UUID.randomUUID();
        UUID contributorTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(contributorId, fixture.contributorTypeId(),
                Map.of(fixture.contributorNamePropertyId(), "Ada"))), contributorTxn, null);
        coordinator.commit(contributorTxn, UUID.randomUUID());
        UUID linkId = createLink(productId, contributorId);
        UUID markerId = authRepo.createMarker("mast-" + UUID.randomUUID(), "test marker").id();
        markerAssignmentService.addLinkMarker(linkId, markerId, null);

        markerAssignmentService.removeLinkMarker(linkId, markerId, null);

        assertThat(authRepo.getMarkerIdsForLink(linkId)).doesNotContain(markerId);
    }
}
