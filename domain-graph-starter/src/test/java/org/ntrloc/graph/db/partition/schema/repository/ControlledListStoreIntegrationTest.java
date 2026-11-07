package org.ntrloc.graph.db.partition.schema.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ControlledListStore's list lifecycle: the catalog row, the per-list value table, and the property
// reference. The DB is shared across the whole test run, so every list here is UUID-named and
// assertions filter by id.
class ControlledListStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ControlledListStore controlledListStore;

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
        var created = controlledListStore.createList(name, PropertyType.STRING);

        assertThat(controlledListStore.getAllLists()).extracting(ControlledListStore.ControlledList::id).contains(created.id());
        assertThat(controlledListStore.getListById(created.id())).hasValueSatisfying(l -> {
            assertThat(l.name()).isEqualTo(name);
            assertThat(l.valueType()).isEqualTo(PropertyType.STRING);
        });
    }

    @Test
    void getListById_forAnUnknownId_isEmpty() {
        assertThat(controlledListStore.getListById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void renameList_changesTheName() {
        var list = controlledListStore.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        String renamed = "Renamed-" + UUID.randomUUID();

        controlledListStore.renameList(list.id(), renamed);

        assertThat(controlledListStore.getListById(list.id())).hasValueSatisfying(l -> assertThat(l.name()).isEqualTo(renamed));
    }

    @Test
    void countValues_reflectsAddedValues() {
        var list = controlledListStore.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        assertThat(controlledListStore.countValues(list.id())).isZero();

        controlledListStore.addValues(list.id(), List.of(
                new ControlledListStore.ValueEntry("A", "Alpha"),
                new ControlledListStore.ValueEntry("B", "Beta")));

        assertThat(controlledListStore.countValues(list.id())).isEqualTo(2);
    }

    @Test
    void clearPropertyControlledList_detachesTheProperty() {
        UUID propertyId = createStringProperty();
        var list = controlledListStore.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListStore.setPropertyControlledList(propertyId, list.id());
        assertThat(controlledListStore.getListForProperty(propertyId)).isPresent();

        controlledListStore.clearPropertyControlledList(propertyId);

        assertThat(controlledListStore.getListForProperty(propertyId)).isEmpty();
    }

    @Test
    void deleteList_dropsTheValueTable_andNullsReferencingProperties() {
        UUID propertyId = createStringProperty();
        var list = controlledListStore.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListStore.addValue(list.id(), "A", "Alpha", 0);
        controlledListStore.setPropertyControlledList(propertyId, list.id());

        controlledListStore.deleteList(list.id());

        assertThat(controlledListStore.getListById(list.id())).isEmpty();
        assertThat(controlledListStore.getListForProperty(propertyId)).isEmpty();
        // The per-list value table is gone -- a follow-up read throws rather than returning stale rows.
        assertThatThrownBy(() -> controlledListStore.getValues(list.id(), PropertyType.STRING))
                .isInstanceOf(Exception.class);
    }
}
