package org.ntrloc.graph.db.coordinator;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerRegisterCoordinatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createItemThenCommit_isVisibleInRegister() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"))), txn, null);
        coordinator.commit(txn, UUID.randomUUID());

        var projected = registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(projected.properties()).containsEntry("name", "Widget");
    }

    @Test
    void updateItemThenCommit_replacesPropertiesAndKeepsSameItemId() {
        UUID itemId = UUID.randomUUID();
        UUID createTxn = UUID.randomUUID();
        UUID updateTxn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), "Widget", fixture.colorPropertyId(), "red"))), createTxn, null);
        coordinator.commit(createTxn, UUID.randomUUID());

        Map<UUID, Object> diff = new java.util.HashMap<>();
        diff.put(fixture.namePropertyId(), "Widget Pro");
        diff.put(fixture.colorPropertyId(), null);
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff)), updateTxn, null);
        coordinator.commit(updateTxn, UUID.randomUUID());

        var projected = registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(projected.properties()).containsEntry("name", "Widget Pro");
        assertThat(projected.properties()).doesNotContainKey("color");

        Long committedRows = jdbcClient.sql("SELECT COUNT(*) FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param("itemId", itemId).query(Long.class).single();
        assertThat(committedRows).isEqualTo(1L);
    }

    @Test
    void deleteItemThenCommit_removesFromRegister() {
        UUID itemId = UUID.randomUUID();
        UUID createTxn = UUID.randomUUID();
        UUID deleteTxn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"))), createTxn, null);
        coordinator.commit(createTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new ItemDeleteEntry(itemId)), deleteTxn, null);
        coordinator.commit(deleteTxn, UUID.randomUUID());

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary")).isEmpty();
    }

    @Test
    void createLinkThenCommit_isVisibleFromBothConnectedItems() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Ada"))
        ), itemsTxn, null);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        LinkCreateEntry linkCreate = new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), "author"));
        coordinator.prepare(List.of(linkCreate), linkTxn, null);
        coordinator.commit(linkTxn, UUID.randomUUID());

        var product = registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary").orElseThrow();
        var contributor = registerPartitionManager.projectOne(fixture.contributorTypeId(), contributorId, "http://binary").orElseThrow();

        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId) && link.properties().get("role").equals("author"));
        assertThat(contributor.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(productId));
    }

    @Test
    void updateLinkThenCommit_preservesEndpointsChangesProperties() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkCreateTxn = UUID.randomUUID();
        UUID linkUpdateTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Ada"))
        ), itemsTxn, null);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), "author"))), linkCreateTxn, null);
        coordinator.commit(linkCreateTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkUpdateEntry(linkId, Map.of(fixture.rolePropertyId(), "editor"))), linkUpdateTxn, null);
        coordinator.commit(linkUpdateTxn, UUID.randomUUID());

        var product = registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId) && link.properties().get("role").equals("editor"));
    }

    @Test
    void deletingLinkedItem_autoCascadesLinkDeleteWithoutCallerHavingToAskForIt() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();
        UUID cascadeTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Ada"))
        ), itemsTxn, null);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), linkTxn, null);
        coordinator.commit(linkTxn, UUID.randomUUID());

        // Only the ItemDeleteEntry is submitted -- the coordinator must discover productId's
        // existing link itself, delete it (before deleting productId, since the FK from
        // register_item_link_perspective to register_item has no cascade), and leave
        // contributorId as a normal surviving item with no dangling link.
        coordinator.prepare(List.of(new ItemDeleteEntry(productId)), cascadeTxn, null);
        coordinator.commit(cascadeTxn, UUID.randomUUID());

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary")).isEmpty();
        var contributor = registerPartitionManager.projectOne(fixture.contributorTypeId(), contributorId, "http://binary").orElseThrow();
        assertThat(contributor.links().values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void deletingBothLinkedItemsTogether_removesSharedLinkExactlyOnceWithNoRippleEitherSide() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();
        UUID cascadeTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Ada"))
        ), itemsTxn, null);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), linkTxn, null);
        coordinator.commit(linkTxn, UUID.randomUUID());

        // Both sides of the shared link are deleted in the same batch -- expansion must dedupe
        // the link (not attempt to delete it twice) and must not add a ripple ItemUpdateEntry
        // for either side, since both are themselves being deleted.
        coordinator.prepare(List.of(new ItemDeleteEntry(productId), new ItemDeleteEntry(contributorId)), cascadeTxn, null);
        coordinator.commit(cascadeTxn, UUID.randomUUID());

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary")).isEmpty();
        assertThat(registerPartitionManager.projectOne(fixture.contributorTypeId(), contributorId, "http://binary")).isEmpty();
    }

    @Test
    void updatingAnItemThatAlreadyHasALink_repointsThePerspectiveAndKeepsTheLinkIntact() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();
        UUID updateTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Ada"))
        ), itemsTxn, null);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), linkTxn, null);
        coordinator.commit(linkTxn, UUID.randomUUID());

        // productId's register_item row gets swapped out for a new one on update -- the
        // existing perspective row from the link created above must be repointed at the new
        // row, or this delete-old-committed-row step would hit an FK violation.
        coordinator.prepare(List.of(new ItemUpdateEntry(productId, Map.of(fixture.namePropertyId(), "Widget Pro"))), updateTxn, null);
        coordinator.commit(updateTxn, UUID.randomUUID());

        var product = registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.properties()).containsEntry("name", "Widget Pro");
        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId));

        var contributor = registerPartitionManager.projectOne(fixture.contributorTypeId(), contributorId, "http://binary").orElseThrow();
        assertThat(contributor.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(productId));
    }

    @Test
    void abort_discardsStagedRegisterRowsAndLedgerEntries() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Ghost"))), txn, null);
        coordinator.abort(txn);

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary")).isEmpty();

        Long staged = jdbcClient.sql("SELECT COUNT(*) FROM register_item WHERE item_id = :itemId")
                .param("itemId", itemId).query(Long.class).single();
        assertThat(staged).isEqualTo(0L);
    }

    private Map<String, Object> mapWithNull(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
