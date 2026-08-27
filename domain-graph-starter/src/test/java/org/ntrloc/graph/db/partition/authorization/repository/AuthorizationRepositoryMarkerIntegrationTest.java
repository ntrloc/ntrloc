package org.ntrloc.graph.db.partition.authorization.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers AuthorizationRepository's marker/grant CRUD and the DB-level correctness constraints
// added in V1_0_1_7 (property_id required/forbidden per operation, NULLS NOT DISTINCT dedup).
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
    void createMarker_persistsAndReturnsRow() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "a description");
        assertThat(marker.id()).isNotNull();
        assertThat(marker.description()).isEqualTo("a description");
    }

    @Test
    void grantMarker_thenFindMarkerGrant_isPresent() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());

        authRepo.grantMarker(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);

        assertThat(authRepo.findMarkerGrant(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null)).isPresent();
    }

    @Test
    void grantMarkerIfAbsent_isIdempotent() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());

        authRepo.grantMarkerIfAbsent(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);
        authRepo.grantMarkerIfAbsent(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);

        Long rows = jdbcClient.sql("SELECT COUNT(*) FROM authorization_grant WHERE marker_id = :markerId")
                .param("markerId", marker.id()).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void deleteMarkerGrant_removesIt() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var group = securityRepo.createGroup("arm-" + UUID.randomUUID());
        authRepo.grantMarker(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);
        UUID grantId = authRepo.findMarkerGrant(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null).orElseThrow();

        authRepo.deleteMarkerGrant(grantId);

        assertThat(authRepo.findMarkerGrant(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null)).isEmpty();
    }

    // getGrantedMarkerIds/getGrantedPropertyIdsByMarker moved to AuthorizationCacheManager (see
    // AuthorizationCacheManagerIntegrationTest) -- reads are cache-backed now, these two methods
    // no longer exist on the repository itself.

    @Test
    void propertyScopedOperation_withoutPropertyId_violatesCheckConstraint() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("arm-" + UUID.randomUUID(), "User", null, false);

        assertThatThrownBy(() -> authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.PROPERTY_READ, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void itemLevelOperation_withPropertyId_violatesCheckConstraint() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("arm-" + UUID.randomUUID(), "User", null, false);

        assertThatThrownBy(() -> authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, fixture.namePropertyId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateItemLevelGrant_violatesUniqueConstraint() {
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("arm-" + UUID.randomUUID(), "User", null, false);
        authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, null);

        // Two item:read grants for the same marker/principal both have property_id = NULL --
        // without NULLS NOT DISTINCT on the unique constraint, Postgres would treat these as
        // non-duplicate (NULL <> NULL) and silently allow both.
        assertThatThrownBy(() -> authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getMarkerIdsForItem_returnsAssignedMarkers() {
        UUID itemId = createItem();
        var marker = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test-actor", "test reason");

        assertThat(authRepo.getMarkerIdsForItem(itemId)).containsExactly(marker.id());
    }

    @Test
    void getMarkerIdsForItems_batchesAcrossMultipleItems() {
        UUID item1 = createItem();
        UUID item2 = createItem();
        var marker1 = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        var marker2 = authRepo.createMarker("arm-" + UUID.randomUUID(), "d");
        markerAssignmentService.addItemMarker(item1, marker1.id(), "test-actor", "test reason");
        markerAssignmentService.addItemMarker(item2, marker2.id(), "test-actor", "test reason");

        Map<UUID, Set<UUID>> byItem = authRepo.getMarkerIdsForItems(Set.of(item1, item2));

        assertThat(byItem.get(item1)).containsExactly(marker1.id());
        assertThat(byItem.get(item2)).containsExactly(marker2.id());
    }
}
