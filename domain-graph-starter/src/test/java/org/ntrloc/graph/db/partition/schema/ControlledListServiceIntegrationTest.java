package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.repository.ControlledListStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ControlledListService's rules: the invariants a list's values hold, and each value type's typing
// and matching. The DB is shared across the whole test run, so every list here is UUID-named.
class ControlledListServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ControlledListService controlledListService;

    @Autowired
    private ControlledListStore controlledListStore;

    // --- addValues' own value/label invariants ---
    //
    // replaceValues (UpdateControlledListMutation, the admin-UI's own "replace" flow) delegates
    // straight to addValues after its own DELETE, so these only need covering through addValues
    // itself -- there's no separate code path to duplicate coverage for.

    @Test
    void addValues_withADuplicateValue_throws() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Alpha"),
                new ControlledListStore.ValueEntry("A", "Alpha Two"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    @Test
    void addValues_withSomeValuesLabeledAndOthersNot_throws() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Alpha"),
                new ControlledListStore.ValueEntry("B", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("either all have a label or all have none");
    }

    @Test
    void addValues_withABlankLabelAmongRealLabels_isTreatedAsUnlabeled_andThrows() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Alpha"),
                new ControlledListStore.ValueEntry("B", "   "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("either all have a label or all have none");
    }

    @Test
    void addValues_withADuplicateLabel_throws() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Same"),
                new ControlledListStore.ValueEntry("B", "Same"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list label");
    }

    @Test
    void addValues_allUnlabeled_succeeds() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", null),
                new ControlledListStore.ValueEntry("B", null)));

        assertThat(controlledListStore.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void addValues_allLabeledWithDistinctValuesAndLabels_succeeds() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        controlledListService.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Alpha"),
                new ControlledListStore.ValueEntry("B", "Beta")));

        assertThat(controlledListStore.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void replaceValues_withADuplicateValue_throws() {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListService.addValues(list.id(), List.of(new ControlledListStore.ValueEntry("A", null)));

        assertThatThrownBy(() -> controlledListService.replaceValues(list.id(), PropertyType.STRING, List.of(
                new ReplaceControlledListMutation.Entry("X", null),
                new ReplaceControlledListMutation.Entry("X", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    // Each list type's values, entered as text the way the admin API sends them.
    private UUID listOf(PropertyType type, String... valuesAndLabels) {
        var list = controlledListService.createList("List-" + UUID.randomUUID(), type);
        List<ReplaceControlledListMutation.Entry> entries = new ArrayList<>();
        for (int i = 0; i < valuesAndLabels.length; i += 2) {
            entries.add(new ReplaceControlledListMutation.Entry(valuesAndLabels[i], valuesAndLabels[i + 1]));
        }
        controlledListService.replaceValues(list.id(), type, entries);
        return list.id();
    }

    @Test
    void anIntegerList_holdsWholeNumbers_andMatchesAnItemsIntegerOrLong() {
        UUID listId = listOf(PropertyType.INTEGER, "10", "Ten", "5000000000", "Lots");

        assertThat(controlledListService.getValues(listId, PropertyType.INTEGER)).extracting(AllowedValue::value)
                .containsExactly(10L, 5_000_000_000L);
        assertThat(controlledListService.containsValue(listId, PropertyType.INTEGER, 10)).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.INTEGER, 5_000_000_000L)).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.INTEGER, "10")).isFalse();
        assertThat(controlledListService.getLabels(listId, PropertyType.INTEGER).labelFor(10)).isEqualTo("Ten");
    }

    @Test
    void aDecimalList_treats2And2Point0AsOneValue() {
        UUID listId = listOf(PropertyType.DECIMAL, "2.0", "Two", "0.5", "Half");

        assertThat(controlledListService.getValues(listId, PropertyType.DECIMAL)).extracting(AllowedValue::value)
                .containsExactly(2.0, 0.5);
        assertThat(controlledListService.containsValue(listId, PropertyType.DECIMAL, 2)).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.DECIMAL, 0.5)).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.DECIMAL, 0.25)).isFalse();
        // A facet's JSONB text, in whichever form the item was saved with.
        assertThat(controlledListService.getLabels(listId, PropertyType.DECIMAL).labelFor("2")).isEqualTo("Two");
        assertThatThrownBy(() -> listOf(PropertyType.DECIMAL, "2", null, "2.00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    @Test
    void aDateList_holdsIsoDates() {
        UUID listId = listOf(PropertyType.DATE, "2026-01-31", "End of January");

        assertThat(controlledListService.getValues(listId, PropertyType.DATE)).extracting(AllowedValue::value)
                .containsExactly("2026-01-31");
        assertThat(controlledListService.containsValue(listId, PropertyType.DATE, "2026-01-31")).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.DATE, "2026-02-01")).isFalse();
        assertThat(controlledListService.containsValue(listId, PropertyType.DATE, "not a date")).isFalse();
        assertThatThrownBy(() -> listOf(PropertyType.DATE, "31/01/2026", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't a valid DATE");
    }

    @Test
    void aDateTimeList_holdsInstants_andMatchesTheSameInstantAtAnyOffset() {
        UUID listId = listOf(PropertyType.DATETIME, "2026-01-31T09:30:00+02:00", "Kick-off");

        assertThat(controlledListService.getValues(listId, PropertyType.DATETIME)).extracting(AllowedValue::value)
                .containsExactly("2026-01-31T07:30:00Z");
        assertThat(controlledListService.containsValue(listId, PropertyType.DATETIME, "2026-01-31T07:30:00Z")).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.DATETIME, "2026-01-31T08:30:00+01:00")).isTrue();
        assertThat(controlledListService.containsValue(listId, PropertyType.DATETIME, "2026-01-31T09:30:00Z")).isFalse();
        assertThat(controlledListService.getLabels(listId, PropertyType.DATETIME).labelFor("2026-01-31T07:30Z")).isEqualTo("Kick-off");
    }
}
