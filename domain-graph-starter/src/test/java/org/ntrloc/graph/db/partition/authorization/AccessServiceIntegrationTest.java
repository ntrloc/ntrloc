package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStore;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// AccessService is the one writer of grants and markers; each of its methods is one transaction, and the
// grants view is rebuilt once that transaction commits.
class AccessServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStore grantStore;

    @Autowired
    private MarkerStore markerStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createMarker() {
        return accessService.createMarker("as-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private UUID createUser() {
        return userStore.createUser("User", "as-" + UUID.randomUUID() + "@example.com", false).id();
    }

    @Test
    void aGrant_isVisibleOnceItsTransactionCommits() {
        UUID marker = createMarker();
        UUID user = createUser();

        accessService.setItemPermissions(marker, "USER", user, true, false);

        assertThat(grantStore.getGrantedItemReadMarkerIds(user, Set.of())).contains(marker);
    }

    // The principal's grant of the marker is created first, then the transition grant fails on its foreign
    // key: both roll back together, so no empty grant is left behind.
    @Test
    void aFailedWrite_leavesNothingBehind() {
        UUID marker = createMarker();
        UUID user = createUser();

        assertThatThrownBy(() -> accessService.grantTransition(marker, "USER", user, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(grantStore.findMarkerGrant(marker, "USER", user)).isEmpty();
    }

    @Test
    void deletingAMarker_removesItsGrantsWithIt() {
        UUID marker = createMarker();
        UUID user = createUser();
        accessService.setItemPermissions(marker, "USER", user, true, false);

        accessService.deleteMarker(marker);

        assertThat(markerStore.getAllMarkers()).noneMatch(m -> m.id().equals(marker));
        assertThat(grantStore.findMarkerGrant(marker, "USER", user)).isEmpty();
        assertThat(grantStore.getGrantedItemReadMarkerIds(user, Set.of())).doesNotContain(marker);
    }

    @Test
    void revokingAnItemTypeGrant_saysWhetherThereWasOne() {
        UUID user = createUser();
        accessService.grantItemType(fixture.productTypeId(), "USER", user, GrantStoreReader.ITEM_TYPE_READ);

        assertThat(accessService.revokeItemType(fixture.productTypeId(), "USER", user, GrantStoreReader.ITEM_TYPE_READ)).isTrue();
        assertThat(accessService.revokeItemType(fixture.productTypeId(), "USER", user, GrantStoreReader.ITEM_TYPE_READ)).isFalse();
        assertThat(grantStore.hasItemTypeGrant(user, Set.of(), fixture.productTypeId(), GrantStoreReader.ITEM_TYPE_READ)).isFalse();
    }
}
