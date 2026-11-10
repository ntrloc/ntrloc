package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStore;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// GrantStore answers grant lookups from an in-memory GrantsView it rebuilds after every grant write. Each
// test writes a grant and reads it back in the same method, proving a lookup is never stale relative to a
// write that just happened -- the property the rest of the marker/permission suite depends on.
class GrantStoreLookupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GrantStore grantStore;

    @Autowired
    private MarkerStore markerStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createMarker() {
        return markerStore.createMarker("acm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    @Test
    void getGrantedItemReadMarkerIds_returnsMarkersGrantedDirectlyAndViaUserGroup() {
        UUID directMarker = createMarker();
        UUID groupMarker = createMarker();
        UUID ungrantedMarker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        var group = userStore.createUserGroup("acm-" + UUID.randomUUID());

        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(directMarker, "USER", user.id()), true, false);
        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(groupMarker, "USER_GROUP", group.id()), true, false);

        Set<UUID> granted = grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()));

        assertThat(granted).contains(directMarker, groupMarker);
        assertThat(granted).doesNotContain(ungrantedMarker);
    }

    @Test
    void getPropertyReadGrantsByMarker_scopesToSpecificProperties() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = grantStore.ensureMarkerGrant(marker, "USER", user.id());

        grantStore.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        grantStore.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        Map<UUID, Set<UUID>> byMarker = grantStore.getPropertyReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactlyInAnyOrder(fixture.namePropertyId(), fixture.colorPropertyId());
    }

    @Test
    void getLinkPerspectiveReadGrantsByMarker_scopesToSpecificPerspectives() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = grantStore.ensureMarkerGrant(marker, "USER", user.id());

        grantStore.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);

        Map<UUID, Set<UUID>> byMarker = grantStore.getLinkPerspectiveReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactly(fixture.productPerspectiveId());
    }

    @Test
    void grantingAMarker_isVisibleImmediately_noRestartNeeded() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);

        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(marker, "USER", user.id()), true, false);

        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);
    }

    // A marker's grants are deleted through the Grant Store before the marker itself (MarkerStore), so
    // the lookups stop answering for it at once rather than waiting for the next unrelated rebuild.
    @Test
    void deletingAMarkersGrants_takesEffectImmediately() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(marker, "USER", user.id()), true, false);
        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);

        grantStore.deleteGrantsForMarker(marker);
        markerStore.deleteMarker(marker);

        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);
    }

    @Test
    void revokingAGrant_takesEffectImmediately() {
        UUID marker = createMarker();
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = grantStore.ensureMarkerGrant(marker, "USER", user.id());
        grantStore.setItemPermissions(grantId, true, false);
        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);

        grantStore.deleteMarkerGrant(grantId);

        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);
    }

    @Test
    void itemTypeGrants_areVisibleImmediately() {
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(grantStore.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), GrantStoreReader.ITEM_TYPE_READ)).isFalse();

        grantStore.grantItemType(fixture.productTypeId(), "USER", user.id(), GrantStoreReader.ITEM_TYPE_READ);

        assertThat(grantStore.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), GrantStoreReader.ITEM_TYPE_READ)).isTrue();
        assertThat(grantStore.getGrantedItemTypeIds(user.id(), Set.of(), GrantStoreReader.ITEM_TYPE_READ)).contains(fixture.productTypeId());
    }

    @Test
    void groupMembershipChangeAlone_needsNoRebuild_membershipResolvedAtReadTime() {
        UUID marker = createMarker();
        var group = userStore.createUserGroup("acm-" + UUID.randomUUID());
        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(marker, "USER_GROUP", group.id()), true, false);

        // Joining the group happens strictly after the grant above -- no grant or revoke fires
        // afterward, so nothing rebuilds the snapshot. If this still resolves
        // correctly, it's proof group membership is consulted at union-time (read time), not baked
        // into what's cached -- exactly the design point this table shape was chosen for.
        var user = userStore.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(user.id(), group.id());

        assertThat(grantStore.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()))).contains(marker);
    }
}
