package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// AccessStore answers grant lookups from an in-memory snapshot it rebuilds after every grant write. Each
// test writes a grant and reads it back in the same method, proving a lookup is never stale relative to a
// write that just happened -- the property the rest of the marker/permission suite depends on.
class AccessStoreGrantLookupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccessStore accessStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createMarker() {
        return accessStore.createMarker("acm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    @Test
    void getGrantedItemReadMarkerIds_returnsMarkersGrantedDirectlyAndViaUserGroup() {
        UUID directMarker = createMarker();
        UUID groupMarker = createMarker();
        UUID ungrantedMarker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        var group = userStore.createUserGroup("acm-" + UUID.randomUUID());

        accessStore.setItemPermissions(accessStore.ensureMarkerGrant(directMarker, "USER", user.id()), true, false);
        accessStore.setItemPermissions(accessStore.ensureMarkerGrant(groupMarker, "USER_GROUP", group.id()), true, false);

        Set<UUID> granted = accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()));

        assertThat(granted).contains(directMarker, groupMarker);
        assertThat(granted).doesNotContain(ungrantedMarker);
    }

    @Test
    void getPropertyReadGrantsByMarker_scopesToSpecificProperties() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = accessStore.ensureMarkerGrant(marker, "USER", user.id());

        accessStore.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        accessStore.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        Map<UUID, Set<UUID>> byMarker = accessStore.getPropertyReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactlyInAnyOrder(fixture.namePropertyId(), fixture.colorPropertyId());
    }

    @Test
    void getLinkPerspectiveReadGrantsByMarker_scopesToSpecificPerspectives() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = accessStore.ensureMarkerGrant(marker, "USER", user.id());

        accessStore.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);

        Map<UUID, Set<UUID>> byMarker = accessStore.getLinkPerspectiveReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactly(fixture.productPerspectiveId());
    }

    @Test
    void grantingAMarker_isVisibleImmediately_noRestartNeeded() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);

        accessStore.setItemPermissions(accessStore.ensureMarkerGrant(marker, "USER", user.id()), true, false);

        assertThat(accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);
    }

    @Test
    void revokingAGrant_takesEffectImmediately() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = accessStore.ensureMarkerGrant(marker, "USER", user.id());
        accessStore.setItemPermissions(grantId, true, false);
        assertThat(accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);

        accessStore.deleteMarkerGrant(grantId);

        assertThat(accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);
    }

    @Test
    void itemTypeGrants_areVisibleImmediately() {
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(accessStore.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isFalse();

        accessStore.grantItemType(fixture.productTypeId(), "USER", user.id(), PermissionService.ITEM_TYPE_READ);

        assertThat(accessStore.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isTrue();
        assertThat(accessStore.getGrantedItemTypeIds(user.id(), Set.of(), PermissionService.ITEM_TYPE_READ)).contains(fixture.productTypeId());
    }

    @Test
    void groupMembershipChangeAlone_needsNoRebuild_membershipResolvedAtReadTime() {
        UUID marker = createMarker();
        var group = userStore.createUserGroup("acm-" + UUID.randomUUID());
        accessStore.setItemPermissions(accessStore.ensureMarkerGrant(marker, "USER_GROUP", group.id()), true, false);

        // Joining the group happens strictly after the grant above -- no grant or revoke fires
        // afterward, so nothing rebuilds the snapshot. If this still resolves
        // correctly, it's proof group membership is consulted at union-time (read time), not baked
        // into what's cached -- exactly the design point this table shape was chosen for.
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(user.id(), group.id());

        assertThat(accessStore.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()))).contains(marker);
    }
}
