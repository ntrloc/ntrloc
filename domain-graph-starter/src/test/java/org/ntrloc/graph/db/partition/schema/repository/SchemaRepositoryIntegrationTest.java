package org.ntrloc.graph.db.partition.schema.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers SchemaRepository's update/delete methods and trait CRUD, none of which were previously
// exercised -- SchemaManager's own tests only ever *create* schema objects (see this class's
// jacoco gap: every create*/getAllX method was already covered, every update*/delete* method
// wasn't). Talks straight to the repository, not through SchemaManager.applyMutations()'s
// higher-level mutation API, since these are plain CRUD wrappers being tested in their own right.
class SchemaRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchemaRepository schemaRepo;

    // --- Items ---

    @Test
    void updateItem_persistsChanges() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "original description");

        schemaRepo.updateItem(item.id(), "Renamed-" + UUID.randomUUID(), "updated description");

        var reloaded = schemaRepo.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.description()).isEqualTo("updated description");
    }

    @Test
    void createItem_withASupertypeAndAbstractFlag_persistsBoth() {
        var supertype = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");

        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d", supertype.id(), true, null);

        assertThat(item.supertypeId()).isEqualTo(supertype.id());
        assertThat(item.abstractType()).isTrue();
        var reloaded = schemaRepo.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.supertypeId()).isEqualTo(supertype.id());
        assertThat(reloaded.abstractType()).isTrue();
    }

    @Test
    void updateItem_withASupertypeAndAbstractFlag_persistsBoth() {
        var firstSupertype = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var secondSupertype = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d", firstSupertype.id(), false, null);

        schemaRepo.updateItem(item.id(), item.name(), item.description(), secondSupertype.id(), true, null);

        var reloaded = schemaRepo.getAllItems().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(reloaded.supertypeId()).isEqualTo(secondSupertype.id());
        assertThat(reloaded.abstractType()).isTrue();
    }

    // --- Traits ---

    @Test
    void createTrait_thenGetAllTraits_includesIt() {
        var trait = schemaRepo.createTrait("Trait-" + UUID.randomUUID(), "a trait");

        assertThat(schemaRepo.getAllTraits()).contains(trait);
    }

    @Test
    void deleteTrait_removesIt() {
        var trait = schemaRepo.createTrait("Trait-" + UUID.randomUUID(), "a trait");

        schemaRepo.deleteTrait(trait.id());

        assertThat(schemaRepo.getAllTraits()).doesNotContain(trait);
    }

    @Test
    void implementTraitAndRemoveTrait_updateTraitIdsByItem() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var trait = schemaRepo.createTrait("Trait-" + UUID.randomUUID(), "d");

        schemaRepo.implementTrait(item.id(), trait.id());
        assertThat(schemaRepo.getTraitIdsByItem().get(item.id())).contains(trait.id());

        schemaRepo.removeTrait(item.id(), trait.id());
        assertThat(schemaRepo.getTraitIdsByItem().getOrDefault(item.id(), java.util.List.of())).doesNotContain(trait.id());
    }

    // --- Properties ---

    @Test
    void updateProperty_persistsChanges() {
        var property = schemaRepo.createProperty("Prop-" + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);

        AdminPropertyDefinitionView updated = schemaRepo.updateProperty(property.id(), "Renamed-" + UUID.randomUUID(),
                "updated", PropertyType.INT, PropertyCardinality.LIST, PropertyUsage.REQUIRED, false);

        assertThat(updated.description()).isEqualTo("updated");
        assertThat(updated.type()).isEqualTo(PropertyType.INT);
        assertThat(updated.cardinality()).isEqualTo(PropertyCardinality.LIST);
        assertThat(updated.usage()).isEqualTo(PropertyUsage.REQUIRED);
    }

    @Test
    void deleteProperty_removesIt_soAssociatingItAfterwardsIsNoLongerPossible() {
        var property = schemaRepo.createProperty("Prop-" + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);

        schemaRepo.deleteProperty(property.id());

        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        assertThatThrownBy(() -> schemaRepo.associateItemProperty(item.id(), property.id()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void associateTraitPropertyAndDissociateItemProperty_updateTheirRespectiveGroupings() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var trait = schemaRepo.createTrait("Trait-" + UUID.randomUUID(), "d");
        var itemProperty = schemaRepo.createProperty("ItemProp-" + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);
        var traitProperty = schemaRepo.createProperty("TraitProp-" + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);

        schemaRepo.associateItemProperty(item.id(), itemProperty.id());
        schemaRepo.associateTraitProperty(trait.id(), traitProperty.id());

        assertThat(schemaRepo.getPropertiesByTrait().get(trait.id()))
                .extracting(AdminPropertyDefinitionView::id).contains(traitProperty.id());
        assertThat(schemaRepo.getPropertiesByItem().get(item.id()))
                .extracting(AdminPropertyDefinitionView::id).contains(itemProperty.id());

        schemaRepo.dissociateItemProperty(item.id(), itemProperty.id());

        assertThat(schemaRepo.getPropertiesByItem().getOrDefault(item.id(), java.util.List.of()))
                .extracting(AdminPropertyDefinitionView::id).doesNotContain(itemProperty.id());
    }

    // --- Links ---

    @Test
    void deleteLink_removesIt() {
        UUID linkId = schemaRepo.createLink();

        schemaRepo.deleteLink(linkId);

        assertThat(schemaRepo.getAllLinkIds()).doesNotContain(linkId);
    }

    @Test
    void dissociateLinkProperty_removesTheAssociation() {
        UUID linkId = schemaRepo.createLink();
        var property = schemaRepo.createProperty("LinkProp-" + UUID.randomUUID(), "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);
        schemaRepo.associateLinkProperty(linkId, property.id());
        assertThat(schemaRepo.getPropertiesByLink().get(linkId))
                .extracting(AdminPropertyDefinitionView::id).contains(property.id());

        schemaRepo.dissociateLinkProperty(linkId, property.id());

        assertThat(schemaRepo.getPropertiesByLink().getOrDefault(linkId, java.util.List.of()))
                .extracting(AdminPropertyDefinitionView::id).doesNotContain(property.id());
    }

    // --- Perspectives ---

    @Test
    void updatePerspective_persistsChanges() {
        var entity = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        UUID linkId = schemaRepo.createLink();
        var perspective = schemaRepo.createPerspective(entity.id(), linkId, "original", "d", 0, 1);

        var updated = schemaRepo.updatePerspective(perspective.id(), "renamed", "updated", 1, 5);

        assertThat(updated.name()).isEqualTo("renamed");
        assertThat(updated.description()).isEqualTo("updated");
        assertThat(updated.minCardinality()).isEqualTo(1);
        assertThat(updated.maxCardinality()).isEqualTo(5);
    }

    // --- State machines ---

    @Test
    void updateStateMachine_persistsChanges() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "original", "d");

        schemaRepo.updateStateMachine(machine.id(), "renamed", "updated");

        var reloaded = schemaRepo.getStateMachinesByItem().get(item.id()).stream()
                .filter(m -> m.id().equals(machine.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.description()).isEqualTo("updated");
    }

    @Test
    void deleteStateMachine_removesIt() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "original", "d");

        schemaRepo.deleteStateMachine(machine.id());

        assertThat(schemaRepo.getStateMachinesByItem().getOrDefault(item.id(), java.util.List.of()))
                .extracting(SchemaRepository.StateMachineRow::id).doesNotContain(machine.id());
    }

    // --- States ---

    @Test
    void updateState_persistsChanges() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "machine", "d");
        var state = schemaRepo.createState(machine.id(), "original", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, "seed-dec");
        assertThat(state.entryMarkerDecisionKey()).isEqualTo("seed-dec");

        schemaRepo.updateState(state.id(), "renamed", "updated", "entry-proc", "exit-proc", "marker-dec");

        var reloaded = schemaRepo.getStatesByStateMachine().get(machine.id()).stream()
                .filter(s -> s.id().equals(state.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.kind()).isEqualTo(SchemaRepository.STATE_KIND_NORMAL);
        assertThat(reloaded.entryProcessId()).isEqualTo("entry-proc");
        assertThat(reloaded.exitProcessId()).isEqualTo("exit-proc");
        assertThat(reloaded.entryMarkerDecisionKey()).isEqualTo("marker-dec");
    }

    @Test
    void deleteState_removesIt() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "machine", "d");
        var state = schemaRepo.createState(machine.id(), "original", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, null);

        schemaRepo.deleteState(state.id());

        assertThat(schemaRepo.getStatesByStateMachine().getOrDefault(machine.id(), java.util.List.of()))
                .extracting(SchemaRepository.StateRow::id).doesNotContain(state.id());
    }

    // --- Transitions ---

    @Test
    void updateTransition_persistsChanges() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "machine", "d");
        var from = schemaRepo.createState(machine.id(), "from", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, null);
        var to = schemaRepo.createState(machine.id(), "to", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, null);
        var transition = schemaRepo.createTransition(from.id(), to.id(), "original", "d", null, null);

        schemaRepo.updateTransition(transition.id(), "renamed", "updated", "guard-proc", "{\"op\":\"AND\"}");

        var reloaded = schemaRepo.getTransitionsByFromState().get(from.id()).stream()
                .filter(t -> t.id().equals(transition.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("renamed");
        assertThat(reloaded.processId()).isEqualTo("guard-proc");
        assertThat(schemaRepo.parseGuardCondition(reloaded.guardCondition()).get("op").asString()).isEqualTo("AND");
    }

    @Test
    void deleteTransition_removesIt() {
        var item = schemaRepo.createItem("Item-" + UUID.randomUUID(), "d");
        var machine = schemaRepo.createStateMachine(item.id(), "machine", "d");
        var from = schemaRepo.createState(machine.id(), "from", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, null);
        var to = schemaRepo.createState(machine.id(), "to", "d", SchemaRepository.STATE_KIND_NORMAL, null, null, null);
        var transition = schemaRepo.createTransition(from.id(), to.id(), "original", "d", null, null);

        schemaRepo.deleteTransition(transition.id());

        assertThat(schemaRepo.getTransitionsByFromState().getOrDefault(from.id(), java.util.List.of()))
                .extracting(SchemaRepository.TransitionRow::id).doesNotContain(transition.id());
    }

    // --- Guard condition JSON helpers ---

    @Test
    void parseGuardCondition_forNull_returnsNull() {
        assertThat(schemaRepo.parseGuardCondition(null)).isNull();
    }

    @Test
    void parseGuardCondition_forMalformedJson_throwsIllegalStateException() {
        assertThatThrownBy(() -> schemaRepo.parseGuardCondition("{not valid json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void serializeGuardCondition_forNull_returnsNull() {
        assertThat(schemaRepo.serializeGuardCondition(null)).isNull();
    }

    @Test
    void serializeGuardCondition_forAJsonNullNode_returnsNull() {
        // A REST payload's "guardCondition": null deserializes to a NullNode, not Java null.
        assertThat(schemaRepo.serializeGuardCondition(schemaRepo.parseGuardCondition("null"))).isNull();
    }

    @Test
    void serializeGuardCondition_roundTripsWithParseGuardCondition() {
        var node = schemaRepo.parseGuardCondition("{\"op\":\"OR\"}");
        assertThat(schemaRepo.serializeGuardCondition(node)).isEqualTo("{\"op\":\"OR\"}");
    }
}
