package org.ntrloc.graph.db.partition.schema.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers SchemaStore's update/delete methods and trait CRUD, none of which were previously
// exercised -- SchemaService's own tests only ever *create* schema objects (see this class's
// jacoco gap: every create*/getAllX method was already covered, every update*/delete* method
// wasn't). Talks straight to the repository, not through SchemaService.applyMutations()'s
// higher-level mutation API, since these are plain CRUD wrappers being tested in their own right.
class SchemaStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchemaStore schemaStore;

    // --- Items ---

    @Test
    void updateItem_persistsChanges() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "original description");

        schemaStore.updateItem(item.id(), "Renamed-" + UUID.randomUUID(), "updated description");

        var reloaded = schemaStore.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.description()).isEqualTo("updated description");
    }

    @Test
    void createItem_withASupertypeAndAbstractFlag_persistsBoth() {
        var supertype = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");

        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d", supertype.id(), true, null);

        assertThat(item.supertypeId()).isEqualTo(supertype.id());
        assertThat(item.abstractType()).isTrue();
        var reloaded = schemaStore.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.supertypeId()).isEqualTo(supertype.id());
        assertThat(reloaded.abstractType()).isTrue();
    }

    @Test
    void updateItem_withASupertypeAndAbstractFlag_persistsBoth() {
        var firstSupertype = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var secondSupertype = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d", firstSupertype.id(), false, null);

        schemaStore.updateItem(item.id(), item.name(), item.description(), secondSupertype.id(), true, null);

        var reloaded = schemaStore.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.supertypeId()).isEqualTo(secondSupertype.id());
        assertThat(reloaded.abstractType()).isTrue();
    }

    // --- Traits ---

    @Test
    void createTrait_thenGetAllTraits_includesIt() {
        var trait = schemaStore.createTrait("Trait-" + UUID.randomUUID(), "a trait");

        assertThat(schemaStore.getAllTraits()).contains(trait);
    }

    @Test
    void deleteTrait_removesIt() {
        var trait = schemaStore.createTrait("Trait-" + UUID.randomUUID(), "a trait");

        schemaStore.deleteTrait(trait.id());

        assertThat(schemaStore.getAllTraits()).doesNotContain(trait);
    }

    @Test
    void implementTraitAndRemoveTrait_updateTraitIdsByItem() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var trait = schemaStore.createTrait("Trait-" + UUID.randomUUID(), "d");

        schemaStore.implementTrait(item.id(), trait.id());
        assertThat(schemaStore.getTraitIdsByItem().get(item.id())).contains(trait.id());

        schemaStore.removeTrait(item.id(), trait.id());
        assertThat(schemaStore.getTraitIdsByItem().getOrDefault(item.id(), java.util.List.of())).doesNotContain(trait.id());
    }

    // --- Properties and groups ---

    private SchemaStore.PropertyOwnerRef itemRef(java.util.UUID id) {
        return new SchemaStore.PropertyOwnerRef(PropertyContainerKind.ITEM, id);
    }

    private AdminPropertyDefinitionView newProperty(SchemaStore.PropertyOwnerRef parent, String prefix) {
        return schemaStore.createProperty(parent, prefix + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);
    }

    @Test
    void updateProperty_persistsChanges() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var property = newProperty(itemRef(item.id()), "Prop-");

        AdminPropertyDefinitionView updated = schemaStore.updateProperty(property.id(), "Renamed-" + UUID.randomUUID(),
                "updated", PropertyCardinality.LIST, PropertyUsage.REQUIRED, false);

        assertThat(updated.description()).isEqualTo("updated");
        assertThat(updated.type()).isEqualTo(PropertyType.STRING);
        assertThat(updated.cardinality()).isEqualTo(PropertyCardinality.LIST);
        assertThat(updated.usage()).isEqualTo(PropertyUsage.REQUIRED);
    }

    @Test
    void deleteProperty_removesItFromItsParent() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var property = newProperty(itemRef(item.id()), "Prop-");

        schemaStore.deleteProperty(property.id());

        assertThat(schemaStore.findProperty(property.id())).isEmpty();
        assertThat(schemaStore.getPropertiesByItem().getOrDefault(item.id(), java.util.List.of()))
                .extracting(AdminPropertyDefinitionView::id).doesNotContain(property.id());
    }

    @Test
    void propertiesAreGroupedUnderTheirSingleParent() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var trait = schemaStore.createTrait("Trait-" + UUID.randomUUID(), "d");
        var itemProperty = newProperty(itemRef(item.id()), "ItemProp-");
        var traitProperty = newProperty(new SchemaStore.PropertyOwnerRef(PropertyContainerKind.TRAIT, trait.id()), "TraitProp-");

        assertThat(schemaStore.getPropertiesByTrait().get(trait.id()))
                .extracting(AdminPropertyDefinitionView::id).containsExactly(traitProperty.id());
        assertThat(schemaStore.getPropertiesByItem().get(item.id()))
                .extracting(AdminPropertyDefinitionView::id).containsExactly(itemProperty.id());
        assertThat(schemaStore.findPropertyParent(itemProperty.id()))
                .hasValue(itemRef(item.id()));
    }

    @Test
    void moveProperty_repointsItsSingleParent() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var group = schemaStore.createGroup(itemRef(item.id()), "group-" + UUID.randomUUID(), "d");
        var groupRef = new SchemaStore.PropertyOwnerRef(PropertyContainerKind.GROUP, group.id());
        var property = newProperty(itemRef(item.id()), "Prop-");

        schemaStore.moveProperty(property.id(), groupRef);

        assertThat(schemaStore.findPropertyParent(property.id())).hasValue(groupRef);
        assertThat(schemaStore.getPropertiesByItem().getOrDefault(item.id(), java.util.List.of()))
                .extracting(AdminPropertyDefinitionView::id).doesNotContain(property.id());
        assertThat(schemaStore.getPropertiesByGroup().get(group.id()))
                .extracting(AdminPropertyDefinitionView::id).containsExactly(property.id());
    }

    @Test
    void groups_nestAndReportEmptiness() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var outer = schemaStore.createGroup(itemRef(item.id()), "outer-" + UUID.randomUUID(), "d");
        var outerRef = new SchemaStore.PropertyOwnerRef(PropertyContainerKind.GROUP, outer.id());
        assertThat(schemaStore.isGroupEmpty(outer.id())).isTrue();

        var inner = schemaStore.createGroup(outerRef, "inner-" + UUID.randomUUID(), "d");

        assertThat(schemaStore.isGroupEmpty(outer.id())).isFalse();
        assertThat(schemaStore.getParentGroupIdByGroup()).containsEntry(inner.id(), outer.id());
        assertThat(schemaStore.findChildNames(outerRef)).containsExactly(inner.name());
    }

    @Test
    void deletingAnItem_cascadesToItsPropertiesAndGroups() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var group = schemaStore.createGroup(itemRef(item.id()), "group-" + UUID.randomUUID(), "d");
        var property = newProperty(new SchemaStore.PropertyOwnerRef(PropertyContainerKind.GROUP, group.id()), "Prop-");

        schemaStore.deleteItem(item.id());

        assertThat(schemaStore.findGroup(group.id())).isEmpty();
        assertThat(schemaStore.findProperty(property.id())).isEmpty();
    }

    // --- Links ---

    @Test
    void deleteLink_removesIt() {
        UUID linkId = schemaStore.createLink();

        schemaStore.deleteLink(linkId);

        assertThat(schemaStore.getAllLinkIds()).doesNotContain(linkId);
    }

    @Test
    void deletingALink_cascadesToItsProperties() {
        UUID linkId = schemaStore.createLink();
        var property = newProperty(new SchemaStore.PropertyOwnerRef(PropertyContainerKind.LINK, linkId), "LinkProp-");
        assertThat(schemaStore.getPropertiesByLink().get(linkId))
                .extracting(AdminPropertyDefinitionView::id).contains(property.id());

        schemaStore.deleteLink(linkId);

        assertThat(schemaStore.findProperty(property.id())).isEmpty();
    }

    // --- Perspectives ---

    @Test
    void updatePerspective_persistsChanges() {
        var entity = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        UUID linkId = schemaStore.createLink();
        var perspective = schemaStore.createPerspective(entity.id(), linkId, "original", "d", 0, 1);

        var updated = schemaStore.updatePerspective(perspective.id(), "renamed", "updated", 1, 5);

        assertThat(updated.name()).isEqualTo("renamed");
        assertThat(updated.description()).isEqualTo("updated");
        assertThat(updated.minCardinality()).isEqualTo(1);
        assertThat(updated.maxCardinality()).isEqualTo(5);
    }

    // --- State machines ---

    @Test
    void updateStateMachine_persistsChanges() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "original", "d");

        schemaStore.updateStateMachine(machine.id(), "renamed", "updated");

        var reloaded = schemaStore.getStateMachinesByItem().get(item.id()).stream()
                .filter(m -> m.id().equals(machine.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.description()).isEqualTo("updated");
    }

    @Test
    void deleteStateMachine_removesIt() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "original", "d");

        schemaStore.deleteStateMachine(machine.id());

        assertThat(schemaStore.getStateMachinesByItem().getOrDefault(item.id(), java.util.List.of()))
                .extracting(SchemaStore.StateMachineRow::id).doesNotContain(machine.id());
    }

    // --- States ---

    @Test
    void updateState_persistsChanges() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "machine", "d");
        var state = schemaStore.createState(machine.id(), "original", "d", SchemaStore.STATE_KIND_NORMAL, null, null);

        schemaStore.updateState(state.id(), "renamed", "updated", "entry-proc", "exit-proc");

        var reloaded = schemaStore.getStatesByStateMachine().get(machine.id()).stream()
                .filter(s -> s.id().equals(state.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.kind()).isEqualTo(SchemaStore.STATE_KIND_NORMAL);
        assertThat(reloaded.entryProcessId()).isEqualTo("entry-proc");
        assertThat(reloaded.exitProcessId()).isEqualTo("exit-proc");
    }

    @Test
    void deleteState_removesIt() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "machine", "d");
        var state = schemaStore.createState(machine.id(), "original", "d", SchemaStore.STATE_KIND_NORMAL, null, null);

        schemaStore.deleteState(state.id());

        assertThat(schemaStore.getStatesByStateMachine().getOrDefault(machine.id(), java.util.List.of()))
                .extracting(SchemaStore.StateRow::id).doesNotContain(state.id());
    }

    // --- Transitions ---

    @Test
    void updateTransition_persistsChanges() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "machine", "d");
        var from = schemaStore.createState(machine.id(), "from", "d", SchemaStore.STATE_KIND_NORMAL, null, null);
        var to = schemaStore.createState(machine.id(), "to", "d", SchemaStore.STATE_KIND_NORMAL, null, null);
        var transition = schemaStore.createTransition(from.id(), to.id(), "original", "d", null, null);

        schemaStore.updateTransition(transition.id(), "renamed", "updated", "guard-proc", "{\"op\":\"AND\"}");

        var reloaded = schemaStore.getTransitionsByFromState().get(from.id()).stream()
                .filter(t -> t.id().equals(transition.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.processId()).isEqualTo("guard-proc");
        assertThat(schemaStore.parseGuardCondition(reloaded.guardCondition()).get("op").asString()).isEqualTo("AND");
    }

    @Test
    void deleteTransition_removesIt() {
        var item = schemaStore.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaStore.createStateMachine(item.id(), "machine", "d");
        var from = schemaStore.createState(machine.id(), "from", "d", SchemaStore.STATE_KIND_NORMAL, null, null);
        var to = schemaStore.createState(machine.id(), "to", "d", SchemaStore.STATE_KIND_NORMAL, null, null);
        var transition = schemaStore.createTransition(from.id(), to.id(), "original", "d", null, null);

        schemaStore.deleteTransition(transition.id());

        assertThat(schemaStore.getTransitionsByFromState().getOrDefault(from.id(), java.util.List.of()))
                .extracting(SchemaStore.TransitionRow::id).doesNotContain(transition.id());
    }

    // --- Guard condition JSON helpers ---

    @Test
    void parseGuardCondition_forNull_returnsNull() {
        assertThat(schemaStore.parseGuardCondition(null)).isNull();
    }

    @Test
    void parseGuardCondition_forMalformedJson_throwsIllegalStateException() {
        assertThatThrownBy(() -> schemaStore.parseGuardCondition("{not valid json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void serializeGuardCondition_forNull_returnsNull() {
        assertThat(schemaStore.serializeGuardCondition(null)).isNull();
    }

    @Test
    void serializeGuardCondition_forAJsonNullNode_returnsNull() {
        // A REST payload's "guardCondition": null deserializes to a NullNode, not Java null.
        assertThat(schemaStore.serializeGuardCondition(schemaStore.parseGuardCondition("null"))).isNull();
    }

    @Test
    void serializeGuardCondition_roundTripsWithParseGuardCondition() {
        var node = schemaStore.parseGuardCondition("{\"op\":\"OR\"}");
        assertThat(schemaStore.serializeGuardCondition(node)).isEqualTo("{\"op\":\"OR\"}");
    }
}
