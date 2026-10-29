package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Direct coverage of ControlledListManager's list-lifecycle methods (getAllLists / getListById /
// renameList / deleteList / clearPropertyControlledList / countValues) added when controlled lists
// became a first-class, reusable schema element. The DB is shared across the whole test run, so
// every list here is UUID-named and assertions filter by id.
class ControlledListManagerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ControlledListManager controlledListManager;

    @Autowired
    private SchemaManager schemaManager;

    private UUID createStringProperty() {
        String itemName = "CLMTest-" + UUID.randomUUID();
        String propName = "prop-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), null, false, null)));
        UUID itemId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(
                PropertyContainerKind.ITEM, itemId, propName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals(propName)).findFirst().orElseThrow().id();
    }

    @Test
    void getAllLists_andGetListById_seeANewlyCreatedList() {
        String name = "List-" + UUID.randomUUID();
        var created = controlledListManager.createList(name, PropertyType.STRING);

        assertThat(controlledListManager.getAllLists()).extracting(ControlledListManager.ControlledList::id).contains(created.id());
        assertThat(controlledListManager.getListById(created.id())).hasValueSatisfying(l -> {
            assertThat(l.name()).isEqualTo(name);
            assertThat(l.valueType()).isEqualTo(PropertyType.STRING);
        });
    }

    @Test
    void getListById_forAnUnknownId_isEmpty() {
        assertThat(controlledListManager.getListById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void renameList_changesTheName() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        String renamed = "Renamed-" + UUID.randomUUID();

        controlledListManager.renameList(list.id(), renamed);

        assertThat(controlledListManager.getListById(list.id())).hasValueSatisfying(l -> assertThat(l.name()).isEqualTo(renamed));
    }

    @Test
    void countValues_reflectsAddedValues() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        assertThat(controlledListManager.countValues(list.id())).isZero();

        controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Alpha"),
                new ControlledListManager.ValueEntry("B", "Beta")));

        assertThat(controlledListManager.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void clearPropertyControlledList_detachesTheProperty() {
        UUID propertyId = createStringProperty();
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.setPropertyControlledList(propertyId, list.id());
        assertThat(controlledListManager.getListForProperty(propertyId)).isPresent();

        controlledListManager.clearPropertyControlledList(propertyId);

        assertThat(controlledListManager.getListForProperty(propertyId)).isEmpty();
    }

    // --- addValues' own value/label invariants ---
    //
    // replaceValues (UpdateControlledListMutation, the admin-UI's own "replace" flow) delegates
    // straight to addValues after its own DELETE, so these only need covering through addValues
    // itself -- there's no separate code path to duplicate coverage for.

    @Test
    void addValues_withADuplicateValue_throws() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Alpha"),
                new ControlledListManager.ValueEntry("A", "Alpha Two"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    @Test
    void addValues_withSomeValuesLabeledAndOthersNot_throws() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Alpha"),
                new ControlledListManager.ValueEntry("B", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("either all have a label or all have none");
    }

    @Test
    void addValues_withABlankLabelAmongRealLabels_isTreatedAsUnlabeled_andThrows() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Alpha"),
                new ControlledListManager.ValueEntry("B", "   "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("either all have a label or all have none");
    }

    @Test
    void addValues_withADuplicateLabel_throws() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Same"),
                new ControlledListManager.ValueEntry("B", "Same"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list label");
    }

    @Test
    void addValues_allUnlabeled_succeeds() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", null),
                new ControlledListManager.ValueEntry("B", null)));

        assertThat(controlledListManager.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void addValues_allLabeledWithDistinctValuesAndLabels_succeeds() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        controlledListManager.addValues(list.id(), List.of(
                new ControlledListManager.ValueEntry("A", "Alpha"),
                new ControlledListManager.ValueEntry("B", "Beta")));

        assertThat(controlledListManager.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void replaceValues_withADuplicateValue_throws() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.addValues(list.id(), List.of(new ControlledListManager.ValueEntry("A", null)));

        assertThatThrownBy(() -> controlledListManager.replaceValues(list.id(), PropertyType.STRING, List.of(
                new ReplaceControlledListMutation.Entry("X", null),
                new ReplaceControlledListMutation.Entry("X", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    // Each list type's values, entered as text the way the admin API sends them.
    private UUID listOf(PropertyType type, String... valuesAndLabels) {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), type);
        List<ReplaceControlledListMutation.Entry> entries = new ArrayList<>();
        for (int i = 0; i < valuesAndLabels.length; i += 2) {
            entries.add(new ReplaceControlledListMutation.Entry(valuesAndLabels[i], valuesAndLabels[i + 1]));
        }
        controlledListManager.replaceValues(list.id(), type, entries);
        return list.id();
    }

    @Test
    void anIntegerList_holdsWholeNumbers_andMatchesAnItemsIntegerOrLong() {
        UUID listId = listOf(PropertyType.INTEGER, "10", "Ten", "5000000000", "Lots");

        assertThat(controlledListManager.getValues(listId, PropertyType.INTEGER)).extracting(AllowedValue::value)
                .containsExactly(10L, 5_000_000_000L);
        assertThat(controlledListManager.containsValue(listId, PropertyType.INTEGER, 10)).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.INTEGER, 5_000_000_000L)).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.INTEGER, "10")).isFalse();
        assertThat(controlledListManager.getLabels(listId, PropertyType.INTEGER).labelFor(10)).isEqualTo("Ten");
    }

    @Test
    void aDecimalList_treats2And2Point0AsOneValue() {
        UUID listId = listOf(PropertyType.DECIMAL, "2.0", "Two", "0.5", "Half");

        assertThat(controlledListManager.getValues(listId, PropertyType.DECIMAL)).extracting(AllowedValue::value)
                .containsExactly(2.0, 0.5);
        assertThat(controlledListManager.containsValue(listId, PropertyType.DECIMAL, 2)).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DECIMAL, 0.5)).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DECIMAL, 0.25)).isFalse();
        // A facet's JSONB text, in whichever form the item was saved with.
        assertThat(controlledListManager.getLabels(listId, PropertyType.DECIMAL).labelFor("2")).isEqualTo("Two");
        assertThatThrownBy(() -> listOf(PropertyType.DECIMAL, "2", null, "2.00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate controlled list value");
    }

    @Test
    void aDateList_holdsIsoDates() {
        UUID listId = listOf(PropertyType.DATE, "2026-01-31", "End of January");

        assertThat(controlledListManager.getValues(listId, PropertyType.DATE)).extracting(AllowedValue::value)
                .containsExactly("2026-01-31");
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATE, "2026-01-31")).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATE, "2026-02-01")).isFalse();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATE, "not a date")).isFalse();
        assertThatThrownBy(() -> listOf(PropertyType.DATE, "31/01/2026", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't a valid DATE");
    }

    @Test
    void aDateTimeList_holdsInstants_andMatchesTheSameInstantAtAnyOffset() {
        UUID listId = listOf(PropertyType.DATETIME, "2026-01-31T09:30:00+02:00", "Kick-off");

        assertThat(controlledListManager.getValues(listId, PropertyType.DATETIME)).extracting(AllowedValue::value)
                .containsExactly("2026-01-31T07:30:00Z");
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATETIME, "2026-01-31T07:30:00Z")).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATETIME, "2026-01-31T08:30:00+01:00")).isTrue();
        assertThat(controlledListManager.containsValue(listId, PropertyType.DATETIME, "2026-01-31T09:30:00Z")).isFalse();
        assertThat(controlledListManager.getLabels(listId, PropertyType.DATETIME).labelFor("2026-01-31T07:30Z")).isEqualTo("Kick-off");
    }

    @Test
    void deleteList_dropsTheValueTable_andNullsReferencingProperties() {
        UUID propertyId = createStringProperty();
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.addValue(list.id(), "A", "Alpha", 0);
        controlledListManager.setPropertyControlledList(propertyId, list.id());

        controlledListManager.deleteList(list.id());

        assertThat(controlledListManager.getListById(list.id())).isEmpty();
        assertThat(controlledListManager.getListForProperty(propertyId)).isEmpty();
        // The per-list value table is gone -- a follow-up read throws rather than returning stale rows.
        assertThatThrownBy(() -> controlledListManager.getValues(list.id(), PropertyType.STRING))
                .isInstanceOf(Exception.class);
    }
}
