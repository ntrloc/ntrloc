package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetItemInitProcessMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers SchemaManager.applyMutations()'s mutation types that nothing else in the suite exercises
// (jacoco showed create/delete-item, create-link-with-perspectives, create-state-machine/state/
// transition already covered via other fixtures -- e.g. RegisterProjectionTestDomainInitializer --
// but every trait mutation, every update*/delete* mutation, and ReplaceControlledListMutation
// were not), plus buildAdminSchema()/buildSchema()'s trait-inheritance branches (a property or
// perspective defined on a trait, surfaced on every item implementing it), which nothing else
// exercises either since no other fixture in the suite gives an item a trait.
//
// One shared trait-implementing-item scenario (see itemWithTraitAndCrossTraitLink()) covers the
// trait-inheritance branches in both buildAdminSchema() and buildSchema() at once, including
// requireKnownItemOrTrait()'s trait-target branch (a link perspective whose target is a trait, not
// an item type -- see that method's own class comment on why that distinction matters) and
// TargetEntityView's "trait" kind (as opposed to "item").
class SchemaManagerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private ControlledListManager controlledListManager;

    private UUID createItem(String name) {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(name, "d", List.of())));
        return findItem(name).id();
    }

    private AdminItemDefinitionView findItem(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private AdminTraitDefinitionView findTrait(String name) {
        return schemaManager.getAdminSchema().traits().stream()
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    // --- Trait CRUD and trait-inheritance (buildAdminSchema/buildSchema) ---

    @Test
    void createTraitDefinitionMutation_withDuplicatePropertyNames_throws() {
        String name = "Trait-" + UUID.randomUUID();
        var duplicateProp = new CreatePropertyDefinitionMutation("dup", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateTraitDefinitionMutation(name, "d", List.of(duplicateProp, duplicateProp)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defined more than once");
    }

    @Test
    void itemImplementingATrait_inheritsTheTraitsPropertiesAndCrossTraitLinkPerspective_inBothAdminAndCalculatedSchema() {
        String itemName = "Item-" + UUID.randomUUID();
        String traitName = "Trait-" + UUID.randomUUID();
        String reviewerName = "Reviewer-" + UUID.randomUUID();

        UUID itemId = createItem(itemName);
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("inheritedProp", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)))));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        UUID reviewerId = createItem(reviewerName);
        // Perspective targets the TRAIT, not the item -- exercises requireKnownItemOrTrait's
        // trait branch and TargetEntityView's "trait" kind, neither reachable via an item-only link.
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(traitId, "reviewers", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(reviewerId, "reviewedTrait", "d", 0, null)))));

        var adminItem = findItem(itemName);
        assertThat(adminItem.traits()).extracting(t -> t.name()).contains(traitName);
        assertThat(adminItem.properties()).extracting(p -> p.name()).contains("inheritedProp");
        assertThat(adminItem.properties()).filteredOn(p -> p.name().equals("inheritedProp"))
                .allSatisfy(p -> assertThat(p.definedIn().entityName()).isEqualTo(traitName));
        assertThat(adminItem.links()).containsKey("reviewers");
        assertThat(adminItem.links().get("reviewers").get(0).targets())
                .extracting(t -> t.kind()).contains("item");

        var reviewerAdminItem = findItem(reviewerName);
        assertThat(reviewerAdminItem.links().get("reviewedTrait").get(0).targets())
                .extracting(t -> t.kind()).contains("trait");

        var calculatedItem = schemaManager.getSchema(SUPERUSER).items().stream()
                .filter(i -> i.name().equals(itemName)).findFirst().orElseThrow();
        assertThat(calculatedItem.properties()).extracting(p -> p.name()).contains("inheritedProp");
        assertThat(calculatedItem.links()).containsKey("reviewers");
    }

    @Test
    void deleteTraitDefinitionMutation_removesIt() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();

        schemaManager.applyMutations(List.of(new DeleteTraitDefinitionMutation(traitId)));

        assertThat(schemaManager.getAdminSchema().traits()).extracting(t -> t.name()).doesNotContain(traitName);
    }

    @Test
    void removeTraitMutation_removesTheImplementation() {
        String itemName = "Item-" + UUID.randomUUID();
        String traitName = "Trait-" + UUID.randomUUID();
        UUID itemId = createItem(itemName);
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));
        assertThat(findItem(itemName).traits()).extracting(t -> t.name()).contains(traitName);

        schemaManager.applyMutations(List.of(new RemoveTraitMutation(itemId, traitId)));

        assertThat(findItem(itemName).traits()).extracting(t -> t.name()).doesNotContain(traitName);
    }

    // --- requireKnownItemOrTrait's unknown-id branch ---

    @Test
    void createLinkDefinitionMutation_withAPerspectiveTargetingAnUnknownId_throws() {
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(UUID.randomUUID(), "ghost", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown item or trait");
    }

    // --- Item/property mutations ---

    @Test
    void updateItemDefinitionMutation_persistsChanges() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        String newName = "Renamed-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, newName, "updated description")));

        var updated = findItem(newName);
        assertThat(updated.id()).isEqualTo(itemId);
        assertThat(updated.description()).isEqualTo("updated description");
    }

    @Test
    void createItemPropertyDefinitionMutation_withACollidingName_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, "shared", "d2", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createLinkPropertyDefinitionMutation_withACollidingName_throws() {
        UUID linkId = createBareLink();
        schemaManager.applyMutations(List.of(new CreateLinkPropertyDefinitionMutation(
                linkId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateLinkPropertyDefinitionMutation(
                linkId, "shared", "d2", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    private UUID createBareLink() {
        String itemAName = "Item-" + UUID.randomUUID();
        String itemBName = "Item-" + UUID.randomUUID();
        UUID itemAId = createItem(itemAName);
        UUID itemBId = createItem(itemBName);
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(itemAId, "toB-" + UUID.randomUUID(), "d", 0, null),
                new CreatePerspectiveDefinitionMutation(itemBId, "toA-" + UUID.randomUUID(), "d", 0, null)))));
        return findItem(itemAName).links().values().stream().findFirst().orElseThrow().get(0).linkId();
    }

    @Test
    void updatePropertyDefinitionMutation_andDeletePropertyDefinitionMutation_persistChanges() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, "original", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));
        UUID propId = findItem(schemaManager.getAdminSchema().items().stream()
                        .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow().name())
                .properties().stream().filter(p -> p.name().equals("original")).findFirst().orElseThrow().id();

        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(
                propId, "renamed", "updated", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED)));
        var afterUpdate = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        assertThat(afterUpdate.properties()).extracting(p -> p.name()).contains("renamed");

        schemaManager.applyMutations(List.of(new DeletePropertyDefinitionMutation(propId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        assertThat(afterDelete.properties()).extracting(p -> p.name()).doesNotContain("renamed");
    }

    @Test
    void setItemInitProcessMutation_persistsIt() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new SetItemInitProcessMutation(itemId, "some-process-id")));

        var updated = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        assertThat(updated.initProcessId()).isEqualTo("some-process-id");
    }

    // --- Link/perspective mutations ---

    @Test
    void deleteLinkDefinitionMutation_removesTheLink() {
        UUID linkId = createBareLink();

        schemaManager.applyMutations(List.of(new DeleteLinkDefinitionMutation(linkId)));

        assertThat(schemaManager.getAdminSchema().links()).extracting(l -> l.id()).doesNotContain(linkId);
    }

    @Test
    void updatePerspectiveDefinitionMutation_persistsChanges() {
        String itemAName = "Item-" + UUID.randomUUID();
        String itemBName = "Item-" + UUID.randomUUID();
        UUID itemAId = createItem(itemAName);
        UUID itemBId = createItem(itemBName);
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(itemAId, "original", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(itemBId, "inverse", "d", 0, 1)))));
        UUID perspectiveId = findItem(itemAName).links().get("original").get(0).id();

        schemaManager.applyMutations(List.of(new UpdatePerspectiveDefinitionMutation(perspectiveId, "renamed", "updated", 1, 5)));

        var updated = findItem(itemAName);
        assertThat(updated.links()).containsKey("renamed");
        var view = updated.links().get("renamed").get(0);
        assertThat(view.description()).isEqualTo("updated");
        assertThat(view.minCardinality()).isEqualTo(1);
        assertThat(view.maxCardinality()).isEqualTo(5);
    }

    @Test
    void updatePerspectiveDefinitionMutation_renamingToACollidingName_throws() {
        UUID itemAId = createItem("Item-" + UUID.randomUUID());
        UUID itemBId = createItem("Item-" + UUID.randomUUID());
        UUID itemCId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(itemAId, "existingName", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(itemBId, "inverse", "d", 0, 1)))));
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(itemAId, "renameMe", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(itemCId, "inverse2", "d", 0, 1)))));
        UUID perspectiveToRename = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemAId)).findFirst().orElseThrow()
                .links().get("renameMe").get(0).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdatePerspectiveDefinitionMutation(perspectiveToRename, "existingName", "d", 0, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already targets a different type");
    }

    // --- Controlled lists ---

    @Test
    void replaceControlledListMutation_replacesTheValues() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, "genre", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));
        UUID propId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("genre")).findFirst().orElseThrow().id();
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.setPropertyControlledList(propId, list.id());
        controlledListManager.addValue(list.id(), "Old", "Old", 0);

        schemaManager.applyMutations(List.of(new ReplaceControlledListMutation(propId,
                List.of(new ReplaceControlledListMutation.Entry("New", "New")))));

        var values = controlledListManager.getValues(list.id(), PropertyType.STRING);
        assertThat(values).extracting(v -> v.value()).containsExactly("New");
    }

    @Test
    void replaceControlledListMutation_forAPropertyWithNoControlledList_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, "plain", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));
        UUID propId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("plain")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ReplaceControlledListMutation(propId, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No controlled list");
    }

    // --- State machine / state / transition update+delete mutations ---

    @Test
    void updateStateMachineMutation_andDeleteStateMachineMutation() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "original", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();

        schemaManager.applyMutations(List.of(new UpdateStateMachineMutation(machineId, "renamed", "updated")));
        var afterUpdate = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0);
        assertThat(afterUpdate.name()).isEqualTo("renamed");

        schemaManager.applyMutations(List.of(new DeleteStateMachineMutation(machineId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines();
        assertThat(afterDelete).isNullOrEmpty();
    }

    @Test
    void updateStateMutation_andDeleteStateMutation() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();
        schemaManager.applyMutations(List.of(new CreateStateMutation(machineId, "original", "d", true, null, null)));
        UUID stateId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().get(0).id();

        schemaManager.applyMutations(List.of(
                new UpdateStateMutation(stateId, "renamed", "updated", false, "entry-proc", "exit-proc")));
        var afterUpdate = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().get(0);
        assertThat(afterUpdate.name()).isEqualTo("renamed");
        assertThat(afterUpdate.isInitial()).isFalse();

        schemaManager.applyMutations(List.of(new DeleteStateMutation(stateId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states();
        assertThat(afterDelete).isEmpty();
    }

    @Test
    void updateTransitionMutation_andDeleteTransitionMutation() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();
        schemaManager.applyMutations(List.of(
                new CreateStateMutation(machineId, "from", "d", true, null, null),
                new CreateStateMutation(machineId, "to", "d", false, null, null)));
        var states = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states();
        UUID fromId = states.stream().filter(s -> s.name().equals("from")).findFirst().orElseThrow().id();
        UUID toId = states.stream().filter(s -> s.name().equals("to")).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new CreateTransitionMutation(fromId, toId, "original", "d", null, null)));
        UUID transitionId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().stream().filter(s -> s.id().equals(fromId)).findFirst().orElseThrow()
                .transitions().get(0).id();

        schemaManager.applyMutations(List.of(
                new UpdateTransitionMutation(transitionId, "renamed", "updated", "guard-proc", null)));
        var afterUpdate = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().stream().filter(s -> s.id().equals(fromId)).findFirst().orElseThrow()
                .transitions().get(0);
        assertThat(afterUpdate.name()).isEqualTo("renamed");
        assertThat(afterUpdate.processId()).isEqualTo("guard-proc");

        schemaManager.applyMutations(List.of(new DeleteTransitionMutation(transitionId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().stream().filter(s -> s.id().equals(fromId)).findFirst().orElseThrow()
                .transitions();
        assertThat(afterDelete).isEmpty();
    }

    private static final org.ntrloc.graph.db.partition.security.ResolvedPrincipal SUPERUSER =
            new org.ntrloc.graph.db.partition.security.ResolvedPrincipal(
                    UUID.randomUUID(), "test-superuser", "Test Superuser", null, java.util.Set.of(), true);
}
