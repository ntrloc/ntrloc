package org.ntrloc.graph.db.partition.authorization.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers AuthorizationRepository's marker/grant CRUD against the marker_grant + child-table shape
// (see docs/ntrloc-marker-admin-ui-design-notes.md). Illegal states that used to need a DB CHECK
// constraint (property_id required only for property-scoped operations) are now structurally
// unrepresentable -- marker_grant_property always has a property_id column, item-level flags live
// on marker_grant with no property_id at all -- so there's nothing left to test a constraint for.
class AuthorizationRepositoryMarkerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private MarkerAssignmentService markerAssignmentService;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID createItem() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    @Test
    void createMarker_persistsScopeAndReturnsRow() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "a description", "ITEM_TYPE", fixture.productTypeId());
        assertThat(marker.id()).isNotNull();
        assertThat(marker.description()).isEqualTo("a description");
        assertThat(marker.scopeKind()).isEqualTo("ITEM_TYPE");
        assertThat(marker.scopeId()).isEqualTo(fixture.productTypeId());
    }

    @Test
    void createMarkerRule_thenDeleteMarkerRule_roundTrips() {
        String name = "arm-rule-" + UUID.randomUUID();
        var rule = authRepo.createMarkerRule(name, fixture.productTypeId(), "someDecisionKey-" + UUID.randomUUID());
        assertThat(authRepo.getAllMarkerRules()).extracting(AuthorizationRepository.MarkerRuleAdminRow::id).contains(rule.id());

        authRepo.deleteMarkerRule(rule.id());

        assertThat(authRepo.getAllMarkerRules()).extracting(AuthorizationRepository.MarkerRuleAdminRow::id).doesNotContain(rule.id());
    }

    @Test
    void ensureMarkerGrant_isIdempotent() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());

        UUID first = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());
        UUID second = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        assertThat(second).isEqualTo(first);
        Long rows = jdbcClient.sql("SELECT COUNT(*) FROM marker_grant WHERE marker_id = :markerId")
                .param("markerId", marker.id()).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void findMarkerGrant_findsAnEnsuredGrant() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        assertThat(authRepo.findMarkerGrant(marker.id(), "GROUP", group.id())).contains(grantId);
    }

    @Test
    void setItemPermissions_persistsTheFlags() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        authRepo.setItemPermissions(grantId, true, false);

        var row = jdbcClient.sql("SELECT item_can_read, item_can_delete FROM marker_grant WHERE id = :id")
                .param("id", grantId).query((rs, n) -> Map.entry(rs.getBoolean("item_can_read"), rs.getBoolean("item_can_delete"))).single();
        assertThat(row.getKey()).isTrue();
        assertThat(row.getValue()).isFalse();
    }

    @Test
    void grantPropertyAccess_upsertsOnConflict() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, true);

        var row = jdbcClient.sql("SELECT can_read, can_write FROM marker_grant_property WHERE marker_grant_id = :id AND property_id = :propId")
                .param("id", grantId).param("propId", fixture.namePropertyId())
                .query((rs, n) -> Map.entry(rs.getBoolean("can_read"), rs.getBoolean("can_write"))).single();
        assertThat(row.getKey()).isTrue();
        assertThat(row.getValue()).isTrue();
        Long rows = jdbcClient.sql("SELECT COUNT(*) FROM marker_grant_property WHERE marker_grant_id = :id")
                .param("id", grantId).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void grantLinkPerspectiveAccess_upsertsOnConflict() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        authRepo.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);
        authRepo.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), true, true, true);

        var row = jdbcClient.sql("""
                SELECT can_create, can_read, can_delete FROM marker_grant_link_perspective
                WHERE marker_grant_id = :id AND perspective_id = :perspId
                """)
                .param("id", grantId).param("perspId", fixture.productPerspectiveId())
                .query((rs, n) -> List.of(rs.getBoolean("can_create"), rs.getBoolean("can_read"), rs.getBoolean("can_delete"))).single();
        assertThat(row).containsExactly(true, true, true);
    }

    @Test
    void grantLinkPropertyAccess_persists() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());

        authRepo.grantLinkPropertyAccess(grantId, fixture.rolePropertyId(), true, true);

        Long rows = jdbcClient.sql("""
                SELECT COUNT(*) FROM marker_grant_link_property WHERE marker_grant_id = :id AND property_id = :propId AND can_read AND can_write
                """)
                .param("id", grantId).param("propId", fixture.rolePropertyId()).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void deleteMarkerGrant_cascadesToChildTables() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "GROUP", group.id());
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        authRepo.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);

        authRepo.deleteMarkerGrant(grantId);

        assertThat(authRepo.findMarkerGrant(marker.id(), "GROUP", group.id())).isEmpty();
        Long propertyRows = jdbcClient.sql("SELECT COUNT(*) FROM marker_grant_property WHERE marker_grant_id = :id")
                .param("id", grantId).query(Long.class).single();
        Long perspectiveRows = jdbcClient.sql("SELECT COUNT(*) FROM marker_grant_link_perspective WHERE marker_grant_id = :id")
                .param("id", grantId).query(Long.class).single();
        assertThat(propertyRows).isEqualTo(0L);
        assertThat(perspectiveRows).isEqualTo(0L);
    }

    @Test
    void getMarkerIdsForItem_returnsAssignedMarkers() {
        UUID itemId = createItem();
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test-actor", "test reason");

        assertThat(authRepo.getMarkerIdsForItem(itemId)).containsExactly(marker.id());
    }

    @Test
    void getMarkerIdsForItems_batchesAcrossMultipleItems() {
        UUID item1 = createItem();
        UUID item2 = createItem();
        var marker1 = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        var marker2 = authRepo.createMarker("arm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId());
        markerAssignmentService.addItemMarker(item1, marker1.id(), "test-actor", "test reason");
        markerAssignmentService.addItemMarker(item2, marker2.id(), "test-actor", "test reason");

        Map<UUID, Set<UUID>> byItem = authRepo.getMarkerIdsForItems(Set.of(item1, item2));

        assertThat(byItem.get(item1)).containsExactly(marker1.id());
        assertThat(byItem.get(item2)).containsExactly(marker2.id());
    }
}
