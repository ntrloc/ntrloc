package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.springframework.beans.factory.annotation.Autowired;

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
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, propName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, List.of())));
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
