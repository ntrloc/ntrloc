package org.ntrloc.graph.db.coordinator;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
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

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of("name", "Widget"))), txn);
        coordinator.commit(txn, UUID.randomUUID());

        var projected = registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(projected.properties()).containsEntry("name", "Widget");
    }

    @Test
    void updateItemThenCommit_replacesPropertiesAndKeepsSameItemId() {
        UUID itemId = UUID.randomUUID();
        UUID createTxn = UUID.randomUUID();
        UUID updateTxn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of("name", "Widget", "color", "red"))), createTxn);
        coordinator.commit(createTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, mapWithNull("name", "Widget Pro", "color", null))), updateTxn);
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

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of("name", "Widget"))), createTxn);
        coordinator.commit(createTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new ItemDeleteEntry(itemId)), deleteTxn);
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
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of("name", "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of("name", "Ada"))
        ), itemsTxn);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        LinkCreateEntry linkCreate = new LinkCreateEntry(linkId, fixture.linkTypeId(),
                List.of(new LinkEndpoint(fixture.productPerspectiveId(), productId),
                        new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId)),
                Map.of("role", "author"));
        coordinator.prepare(List.of(linkCreate), linkTxn);
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
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of("name", "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of("name", "Ada"))
        ), itemsTxn);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                List.of(new LinkEndpoint(fixture.productPerspectiveId(), productId),
                        new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId)),
                Map.of("role", "author"))), linkCreateTxn);
        coordinator.commit(linkCreateTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkUpdateEntry(linkId, Map.of("role", "editor"))), linkUpdateTxn);
        coordinator.commit(linkUpdateTxn, UUID.randomUUID());

        var product = registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary").orElseThrow();
        assertThat(product.links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId) && link.properties().get("role").equals("editor"));
    }

    @Test
    void itemDeleteCascadedWithLinkDelete_inSameTransaction_succeeds() {
        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID itemsTxn = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();
        UUID cascadeTxn = UUID.randomUUID();

        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of("name", "Widget")),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of("name", "Ada"))
        ), itemsTxn);
        coordinator.commit(itemsTxn, UUID.randomUUID());

        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                List.of(new LinkEndpoint(fixture.productPerspectiveId(), productId),
                        new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId)),
                Map.of())), linkTxn);
        coordinator.commit(linkTxn, UUID.randomUUID());

        // Deleting productId while it's still linked -- the link must be deleted in the same
        // transaction, and the coordinator must apply the link delete before the item delete so
        // the FK from register_item_link_perspective to register_item isn't violated.
        coordinator.prepare(List.of(new ItemDeleteEntry(productId), new LinkDeleteEntry(linkId)), cascadeTxn);
        coordinator.commit(cascadeTxn, UUID.randomUUID());

        assertThat(registerPartitionManager.projectOne(fixture.productTypeId(), productId, "http://binary")).isEmpty();
        var contributor = registerPartitionManager.projectOne(fixture.contributorTypeId(), contributorId, "http://binary").orElseThrow();
        assertThat(contributor.links().values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void abort_discardsStagedRegisterRowsAndLedgerEntries() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of("name", "Ghost"))), txn);
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
