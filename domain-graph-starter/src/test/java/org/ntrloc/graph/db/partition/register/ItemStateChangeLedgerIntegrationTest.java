package org.ntrloc.graph.db.partition.register;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// EntityManager.setItemState is ledger-backed -- it builds an ItemUpdateEntry with only its
// stateChanges facet populated, staged/committed through the same LedgerRegisterCoordinator every
// other mutation uses, instead of the old direct, unaudited register UPDATE. This covers that
// write path specifically -- read-side behavior (projectOne reflecting the new state,
// StateValuePredicate filtering, state facets) is already covered by
// RegisterPartitionManagerProjectionIntegrationTest and unaffected by this change.
class ItemStateChangeLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID createBook(String title) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.bookTypeId(),
                Map.of(fixture.titlePropertyId(), title), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    @Test
    void setItemState_writesAnItemUpdateLedgerEntryWithAStateChangesFacet() {
        UUID bookId = createBook("Ledger Test Book");

        entityManager.setItemState(bookId, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        Long ledgerRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE' AND state = 'COMMITTED'
                """)
                .param("itemId", bookId).query(Long.class).single();
        assertThat(ledgerRows).isEqualTo(1L);
    }

    @Test
    void propertyUpdateAfterStateChange_doesNotWipeTheState() {
        // Regression guard for a real bug the ledger-backing change fixed: register_item rows are
        // swapped wholesale on any update (never patched in place), so carrying `states` forward on
        // every property-only update matters regardless of which facet of ItemUpdateEntry triggered
        // the write.
        UUID bookId = createBook("Carry-Forward Test Book");
        entityManager.setItemState(bookId, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        UUID updateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(bookId, Map.of(fixture.pageCountPropertyId(), 250), Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, null);
        coordinator.commit(updateTxn, UUID.randomUUID());

        var book = registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary").orElseThrow();
        assertThat(book.states().get(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE).currentState())
                .isEqualTo(RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);
        assertThat(book.properties()).containsEntry("pageCount", 250);
    }

    @Test
    void stateChangeAfterPropertyUpdate_doesNotWipeExistingProperties() {
        UUID bookId = createBook("Symmetric Test Book");
        UUID updateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(bookId, Map.of(fixture.pageCountPropertyId(), 400), Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, null);
        coordinator.commit(updateTxn, UUID.randomUUID());

        entityManager.setItemState(bookId, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        var book = registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary").orElseThrow();
        assertThat(book.properties()).containsEntry("title", "Symmetric Test Book");
        assertThat(book.properties()).containsEntry("pageCount", 400);
        assertThat(book.states().get(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE).currentState())
                .isEqualTo(RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);
    }

    @Test
    void propertyAndStateChange_asOneEntry_bothApplyInASingleCommittedRow() {
        // Properties and state are two facets of the same ItemUpdateEntry, not two separate entries
        // that need reconciling -- this proves one entry with both facets populated produces exactly
        // one committed row carrying both changes, with a single ledger entry recording them together.
        UUID bookId = createBook("Combined Change Book");
        UUID stateMachineId = registerPartitionManager.resolveStateMachineId(fixture.bookTypeId(),
                RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE);
        UUID stateId = registerPartitionManager.resolveStateId(stateMachineId,
                RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(bookId, Map.of(fixture.pageCountPropertyId(), 500),
                Map.of(stateMachineId, stateId), Set.of(), Set.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());

        var book = registerPartitionManager.projectOne(fixture.bookTypeId(), bookId, "http://binary").orElseThrow();
        assertThat(book.properties()).containsEntry("pageCount", 500);
        assertThat(book.states().get(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE).currentState())
                .isEqualTo(RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);

        Long committedRows = jdbcClient.sql("SELECT COUNT(*) FROM register_item WHERE item_id = :itemId AND state = 'COMMITTED'")
                .param("itemId", bookId).query(Long.class).single();
        Long uncommittedRows = jdbcClient.sql("SELECT COUNT(*) FROM register_item WHERE item_id = :itemId AND state = 'UNCOMMITTED'")
                .param("itemId", bookId).query(Long.class).single();
        assertThat(committedRows).isEqualTo(1L);
        assertThat(uncommittedRows).isEqualTo(0L);

        Long ledgerEntryCount = jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry WHERE target_id = :itemId AND transaction_id = :txn AND state = 'COMMITTED'
                """)
                .param("itemId", bookId).param("txn", txn).query(Long.class).single();
        assertThat(ledgerEntryCount).isEqualTo(1L);
    }

    @Test
    void setItemStateForUnknownItem_throwsIllegalArgumentException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> entityManager.setItemState(UUID.randomUUID(),
                        RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, RegisterProjectionTestDomainInitializer.OUT_OF_STOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
