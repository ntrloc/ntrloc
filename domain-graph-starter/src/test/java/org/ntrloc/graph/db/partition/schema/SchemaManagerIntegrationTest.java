package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
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
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(name, "d", List.of(), null, false, null)));
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
        var duplicateProp = new CreatePropertyDefinitionMutation("dup", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false);

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
                new CreatePropertyDefinitionMutation("inheritedProp", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
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
        // A trait's contributions sit under a namespace group named for the trait, not flat on the item.
        assertThat(adminItem.properties()).extracting(p -> p.name()).doesNotContain("inheritedProp");
        assertThat(adminItem.groups()).filteredOn(g -> g.name().equals(traitName)).singleElement().satisfies(g -> {
            assertThat(g.traitNamespace()).isTrue();
            assertThat(g.definedIn().entityName()).isEqualTo(traitName);
            assertThat(g.properties()).extracting(p -> p.name()).containsExactly("inheritedProp");
        });
        // Trait-contributed perspectives are namespaced by the trait's name too.
        assertThat(adminItem.links()).containsKey(traitName + ".reviewers");
        assertThat(adminItem.links().get(traitName + ".reviewers").get(0).targets())
                .extracting(t -> t.kind()).contains("item");

        var reviewerAdminItem = findItem(reviewerName);
        assertThat(reviewerAdminItem.links().get("reviewedTrait").get(0).targets())
                .extracting(t -> t.kind()).contains("trait");

        var calculatedItem = schemaManager.getSchema(SUPERUSER).items().stream()
                .filter(i -> i.name().equals(itemName)).findFirst().orElseThrow();
        assertThat(calculatedItem.groups()).filteredOn(g -> g.name().equals(traitName)).singleElement()
                .satisfies(g -> assertThat(g.properties()).extracting(p -> p.name()).containsExactly("inheritedProp"));
        assertThat(calculatedItem.links()).containsKey(traitName + ".reviewers");
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

    // --- requireTraitNotInUse's actually-in-use branch (message-resolution) ---

    @Test
    void deleteTraitDefinitionMutation_forATraitStillImplementedByAnItemType_throwsWithItsName() {
        String itemName = "Item-" + UUID.randomUUID();
        String traitName = "Trait-" + UUID.randomUUID();
        UUID itemId = createItem(itemName);
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeleteTraitDefinitionMutation(traitId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still implemented by an item type")
                .hasMessageContaining(traitName);
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

        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, newName, "updated description", null, false, null)));

        var updated = findItem(newName);
        assertThat(updated.id()).isEqualTo(itemId);
        assertThat(updated.description()).isEqualTo("updated description");
    }

    @Test
    void createItemPropertyDefinitionMutation_withACollidingName_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "shared", "d2", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createLinkPropertyDefinitionMutation_withACollidingName_throws() {
        UUID linkId = createBareLink();
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.LINK, linkId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.LINK, linkId, "shared", "d2", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
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
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "original", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        UUID propId = findItem(schemaManager.getAdminSchema().items().stream()
                        .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow().name())
                .properties().stream().filter(p -> p.name().equals("original")).findFirst().orElseThrow().id();

        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(
                propId, "renamed", "updated", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));
        var afterUpdate = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        assertThat(afterUpdate.properties()).extracting(p -> p.name()).contains("renamed");

        schemaManager.applyMutations(List.of(new DeletePropertyDefinitionMutation(propId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        assertThat(afterDelete.properties()).extracting(p -> p.name()).doesNotContain("renamed");
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
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "genre", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
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
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "plain", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        UUID propId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("plain")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ReplaceControlledListMutation(propId, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No controlled list");
    }

    // --- List-centric controlled-list mutations (ControlledListMutationApplier) ---

    private UUID createStringProp(UUID itemId, String propName) {
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, propName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals(propName)).findFirst().orElseThrow().id();
    }

    @Test
    void createControlledListMutation_createsAReusableList_withValues() {
        String name = "List-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(new CreateControlledListMutation(name, PropertyType.STRING,
                List.of(new CreateControlledListMutation.Entry("A", "Alpha"),
                        new CreateControlledListMutation.Entry("B", "Beta")))));

        var list = controlledListManager.getAllLists().stream()
                .filter(l -> l.name().equals(name)).findFirst().orElseThrow();
        assertThat(controlledListManager.getValues(list.id(), PropertyType.STRING))
                .extracting(v -> v.value()).containsExactly("A", "B");
    }

    @Test
    void createControlledListMutation_withACaseInsensitivelyDuplicateName_throws() {
        String name = "Dup-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(name, PropertyType.STRING, List.of())));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateControlledListMutation(name.toUpperCase(), PropertyType.STRING, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateControlledListMutation_renamesWithoutTouchingValues_whenValuesAreNull() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.addValue(list.id(), "keep", "keep", 0);
        String renamed = "Renamed-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(new UpdateControlledListMutation(list.id(), renamed, null)));

        assertThat(controlledListManager.getListById(list.id())).hasValueSatisfying(l -> assertThat(l.name()).isEqualTo(renamed));
        assertThat(controlledListManager.getValues(list.id(), PropertyType.STRING)).extracting(v -> v.value()).containsExactly("keep");
    }

    @Test
    void updateControlledListMutation_replacesValuesWithoutRenaming_whenNameIsNull() {
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.addValue(list.id(), "old", "old", 0);

        schemaManager.applyMutations(List.of(new UpdateControlledListMutation(list.id(), null,
                List.of(new UpdateControlledListMutation.Entry("new", "new")))));

        assertThat(controlledListManager.getValues(list.id(), PropertyType.STRING)).extracting(v -> v.value()).containsExactly("new");
    }

    @Test
    void deleteControlledListMutation_detachesProperties_andDropsTheValueTable() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "backed");
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);
        controlledListManager.addValue(list.id(), "A", "A", 0);
        controlledListManager.setPropertyControlledList(propId, list.id());

        schemaManager.applyMutations(List.of(new DeleteControlledListMutation(list.id())));

        assertThat(controlledListManager.getListById(list.id())).isEmpty();
        assertThat(controlledListManager.getListForProperty(propId)).isEmpty();
    }

    @Test
    void setPropertyControlledListMutation_attachesAndDetaches() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "genre");
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, list.id())));
        assertThat(controlledListManager.getListForProperty(propId)).hasValueSatisfying(l -> assertThat(l.id()).isEqualTo(list.id()));

        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, null)));
        assertThat(controlledListManager.getListForProperty(propId)).isEmpty();
    }

    @Test
    void setPropertyControlledListMutation_onANonStringProperty_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "flag", "d", PropertyType.BOOLEAN, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        UUID propId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("flag")).findFirst().orElseThrow().id();
        var list = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.STRING);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, list.id()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void setPropertyControlledListMutation_withAValueTypeMismatch_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "str");
        var intList = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.INT);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, intList.id()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createThenSetPropertyControlledList_inSeparateBatches_surfacesInGetAdminSchema() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "status");
        String listName = "List-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(new CreateControlledListMutation(listName, PropertyType.STRING,
                List.of(new CreateControlledListMutation.Entry("Open", "Open")))));
        UUID listId = controlledListManager.getAllLists().stream()
                .filter(l -> l.name().equals(listName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, listId)));

        var schema = schemaManager.getAdminSchema();
        var prop = schema.items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.id().equals(propId)).findFirst().orElseThrow();
        assertThat(prop.controlledListId()).isEqualTo(listId);

        var listView = schema.controlledLists().stream().filter(l -> l.id().equals(listId)).findFirst().orElseThrow();
        assertThat(listView.valueCount()).isEqualTo(1);
        assertThat(listView.usedBy()).extracting(u -> u.propertyId()).contains(propId);
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
        schemaManager.applyMutations(List.of(new CreateStateMutation(machineId, "original", "d", null, null, null)));
        UUID stateId = normalStateNamed(itemId, "original").id();

        schemaManager.applyMutations(List.of(
                new UpdateStateMutation(stateId, "renamed", "updated", "entry-proc", "exit-proc", "marker-dec")));
        var afterUpdate = normalStateNamed(itemId, "renamed");
        assertThat(afterUpdate.name()).isEqualTo("renamed");
        assertThat(afterUpdate.kind()).isEqualTo("NORMAL");
        assertThat(afterUpdate.entryMarkerDecisionKey()).isEqualTo("marker-dec");

        schemaManager.applyMutations(List.of(new DeleteStateMutation(stateId)));
        var afterDelete = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states();
        // The user-defined state is gone; the START/END pseudostates remain.
        assertThat(afterDelete).extracting(s -> s.name()).containsExactlyInAnyOrder("__start__", "__end__");
    }

    // The user-defined (NORMAL) state of a given name within the item's single state machine.
    private org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView normalStateNamed(UUID itemId, String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void createStateMachine_alsoCreatesStartAndEndPseudostates() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));

        var states = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states();
        assertThat(states).extracting(s -> s.kind()).containsExactlyInAnyOrder("START", "END");
        assertThat(states).extracting(s -> s.name()).containsExactlyInAnyOrder("__start__", "__end__");
    }

    @Test
    void deleteStateMutation_onAPseudostate_isRejected() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID startId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states().stream()
                .filter(s -> s.kind().equals("START")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeleteStateMutation(startId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void createStateMutation_withAReservedName_isRejected() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateStateMutation(machineId, "__start__", "d", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void transitionsAroundPseudostates_enforceTheStructuralRules() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();
        schemaManager.applyMutations(List.of(
                new CreateStateMutation(machineId, "s1", "d", null, null, null),
                new CreateStateMutation(machineId, "s2", "d", null, null, null)));
        var states = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).states();
        UUID startId = states.stream().filter(s -> s.kind().equals("START")).findFirst().orElseThrow().id();
        UUID endId = states.stream().filter(s -> s.kind().equals("END")).findFirst().orElseThrow().id();
        UUID s1 = states.stream().filter(s -> s.name().equals("s1")).findFirst().orElseThrow().id();
        UUID s2 = states.stream().filter(s -> s.name().equals("s2")).findFirst().orElseThrow().id();

        // into START -> rejected
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateTransitionMutation(s1, startId, "x", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("START");
        // out of END -> rejected
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateTransitionMutation(endId, s1, "x", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("END");
        // START -> s1 with a guard -> rejected
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateTransitionMutation(startId, s1, "start", null, null, guardJson()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("guard");
        // START -> s1 (no guard) -> ok; a second outgoing from START -> rejected
        schemaManager.applyMutations(List.of(new CreateTransitionMutation(startId, s1, "start", null, null, null)));
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateTransitionMutation(startId, s2, "start2", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outgoing");
    }

    private static tools.jackson.databind.JsonNode guardJson() {
        return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("op", "AND");
    }

    @Test
    void updateTransitionMutation_andDeleteTransitionMutation() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();
        schemaManager.applyMutations(List.of(
                new CreateStateMutation(machineId, "from", "d", null, null, null),
                new CreateStateMutation(machineId, "to", "d", null, null, null)));
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

    // --- Item type inheritance ---

    @Test
    void itemWithASupertype_inheritsItsProperties_inBothAdminAndCalculatedSchema() {
        String vehicleName = "Item-" + UUID.randomUUID();
        String carName = "Item-" + UUID.randomUUID();
        UUID vehicleId = createItem(vehicleName);
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null)));

        var car = findItem(carName);
        assertThat(car.supertypeId()).isEqualTo(vehicleId);
        assertThat(car.properties()).extracting(p -> p.name()).contains("wheels", "doors");
        assertThat(car.properties()).filteredOn(p -> p.name().equals("wheels"))
                .allSatisfy(p -> {
                    assertThat(p.definedIn()).isNotNull();
                    assertThat(p.definedIn().entityType()).isEqualTo("supertype");
                    assertThat(p.definedIn().entityName()).isEqualTo(vehicleName);
                });
        assertThat(car.properties()).filteredOn(p -> p.name().equals("doors"))
                .allSatisfy(p -> assertThat(p.definedIn()).isNull());

        var calculatedCar = schemaManager.getSchema(SUPERUSER).items().stream()
                .filter(i -> i.name().equals(carName)).findFirst().orElseThrow();
        assertThat(calculatedCar.properties()).extracting(p -> p.name()).contains("wheels", "doors");
        assertThat(calculatedCar.supertypeId()).isEqualTo(vehicleId);
    }

    @Test
    void multiLevelSupertypeChain_accumulatesPropertiesFromEveryAncestor() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        String sportsCarName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(sportsCarName, "d", List.of(
                new CreatePropertyDefinitionMutation("topSpeed", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                carId, false, null)));

        var sportsCar = findItem(sportsCarName);
        assertThat(sportsCar.properties()).extracting(p -> p.name()).contains("wheels", "doors", "topSpeed");
    }

    @Test
    void createItemDefinitionMutation_reDeclaringAPropertyNameFromItsSupertype_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "Item-" + UUID.randomUUID(), "d", List.of(
                        new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("collides");
    }

    @Test
    void createItemDefinitionMutation_reDeclaringAPropertyNameFromAMultiHopSupertypeChain_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "Item-" + UUID.randomUUID(), "d", List.of(
                        new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                carId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels");
    }

    @Test
    void createItemPropertyDefinitionMutation_reDeclaringAPropertyNameFromItsSupertype_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, carId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("collides");
    }

    @Test
    void updateItemDefinitionMutation_reParentingIntoACollisionWithItsOwnProperty_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String boatName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(boatName, "d", List.of(
                new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID boatId = findItem(boatName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(boatId, boatName, "d", vehicleId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("collides");
    }

    @Test
    void itemWithBothATraitAndASupertype_inheritsFromBoth() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("insured", "d", PropertyType.BOOLEAN, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null)));
        UUID carId = findItem(carName).id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(carId, traitId)));

        var car = findItem(carName);
        assertThat(car.properties()).extracting(p -> p.name()).containsExactlyInAnyOrder("doors", "wheels");
        assertThat(car.groups()).filteredOn(g -> g.name().equals(traitName)).singleElement()
                .satisfies(g -> assertThat(g.properties()).extracting(p -> p.name()).containsExactly("insured"));
    }

    @Test
    void linkPerspectiveDeclaredOnASupertype_isInheritedBySubtype() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        UUID ownerId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(vehicleId, "ownedBy", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(ownerId, "owns", "d", 0, null)))));

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        var car = findItem(carName);
        assertThat(car.links()).containsKey("ownedBy");
        assertThat(car.links().get("ownedBy").get(0).definedIn()).isNotNull();
        assertThat(car.links().get("ownedBy").get(0).definedIn().entityType()).isEqualTo("supertype");
    }

    @Test
    void stateMachineDeclaredOnASupertype_isInheritedBySubtype() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(vehicleId, "lifecycle", "d")));

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        var car = findItem(carName);
        assertThat(car.stateMachines()).extracting(m -> m.name()).contains("lifecycle");
    }

    @Test
    void createItemDefinitionMutation_withAnUnknownSupertype_throws() {
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation("Item-" + UUID.randomUUID(), "d", List.of(), UUID.randomUUID(), false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown item type");
    }

    @Test
    void updateItemDefinitionMutation_withATraitIdAsSupertype_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(itemId, "Item-" + UUID.randomUUID(), "d", traitId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown item type");
    }

    @Test
    void updateItemDefinitionMutation_settingSupertypeToItself_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(itemId, "Item-" + UUID.randomUUID(), "d", itemId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void updateItemDefinitionMutation_creatingAMultiHopSupertypeCycle_throws() {
        String aName = "Item-" + UUID.randomUUID();
        UUID aId = createItem(aName);
        String bName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(bName, "d", List.of(), aId, false, null)));
        UUID bId = findItem(bName).id();
        String cName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(cName, "d", List.of(), bId, false, null)));
        UUID cId = findItem(cName).id();

        // Chain: C's supertype is B, B's supertype is A. Setting A's supertype to C would close
        // the loop (A -> C -> B -> A).
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(aId, aName, "d", cId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void updateItemDefinitionMutation_changingSupertype_succeedsWithNoInUseGuard() {
        UUID firstSupertypeId = createItem("Item-" + UUID.randomUUID());
        UUID secondSupertypeId = createItem("Item-" + UUID.randomUUID());
        String itemName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), firstSupertypeId, false, null)));
        UUID itemId = findItem(itemName).id();
        assertThat(findItem(itemName).supertypeId()).isEqualTo(firstSupertypeId);

        // Unlike deletion (requireItemTypeNotInUse), re-parenting has no "in use" guard at all --
        // this is intentionally live/forward-only, matching how trait reassignment already works.
        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, itemName, "d", secondSupertypeId, false, null)));

        assertThat(findItem(itemName).supertypeId()).isEqualTo(secondSupertypeId);
    }

    @Test
    void abstractFlag_roundTripsThroughCreateAndUpdate_andCanBeToggledFreely() {
        String itemName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), null, true, null)));
        UUID itemId = findItem(itemName).id();
        assertThat(findItem(itemName).abstractType()).isTrue();

        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, itemName, "d", null, false, null)));
        assertThat(findItem(itemName).abstractType()).isFalse();

        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, itemName, "d", null, true, null)));
        assertThat(findItem(itemName).abstractType()).isTrue();
    }

    // --- Property groups and sibling-name uniqueness ---

    private static final PropertyContainerKind ITEM = PropertyContainerKind.ITEM;
    private static final PropertyContainerKind GROUP = PropertyContainerKind.GROUP;

    private AdminPropertyDefinitionView findProperty(UUID itemId, String name) {
        return itemView(itemId).properties().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private AdminItemDefinitionView itemView(UUID itemId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    }

    private AdminPropertyGroupView findGroup(UUID itemId, String name) {
        return itemView(itemId).groups().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    // Only item-owned groups are looked up afterwards; nested groups are reached through findGroup.
    private UUID createGroup(PropertyContainerKind parentKind, UUID parentId, String name) {
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(parentKind, parentId, name, "d", List.of(), List.of())));
        return findGroup(parentId, name).id();
    }

    private void addProperty(PropertyContainerKind parentKind, UUID parentId, String name, PropertyType type) {
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(parentKind, parentId, name, "d", type, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
    }

    @Test
    void addPropertyGroupDefinitionMutation_addsAGroupThatHoldsProperties_visibleInEffectiveGroups() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");

        addProperty(GROUP, dimensionsId, "length", PropertyType.INT);

        assertThat(findGroup(itemId, "dimensions").properties())
                .extracting(AdminPropertyDefinitionView::name).containsExactly("length");
        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).doesNotContain("length");
    }

    @Test
    void addPropertyDefinitionMutation_onALinkOwnedGroup_isVisibleOnTheLink() {
        UUID linkId = createBareLink();
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.LINK, linkId, "details", "d", List.of(), List.of())));
        UUID detailsId = schemaManager.getAdminSchema().links().stream()
                .filter(l -> l.id().equals(linkId)).findFirst().orElseThrow()
                .groups().get(0).id();

        addProperty(GROUP, detailsId, "role", PropertyType.STRING);

        var details = schemaManager.getAdminSchema().links().stream()
                .filter(l -> l.id().equals(linkId)).findFirst().orElseThrow().groups().get(0);
        assertThat(details.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("role");
    }

    @Test
    void addPropertyDefinitionMutation_toAnUnknownGroup_throws() {
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(
                GROUP, UUID.randomUUID(), "child", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown group");
    }

    @Test
    void aTraitsContributions_appearUnderANamespaceGroupNamedForTheTrait_onImplementingItems() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("name", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        var namespace = findGroup(itemId, traitName);
        assertThat(namespace.traitNamespace()).isTrue();
        assertThat(namespace.definedIn().entityName()).isEqualTo(traitName);
        assertThat(namespace.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("name");
        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).doesNotContain("name");
        assertThat(itemView(itemId).sortableFields()).extracting(f -> f.name()).contains(traitName + ".name");
    }

    @Test
    void aTraitOwnedGroup_isNestedInsideTheTraitNamespace() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.TRAIT, traitId, "file", "d",
                List.of(new CreatePropertyDefinitionMutation("name", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), List.of())));
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        var file = findGroup(itemId, traitName).groups().get(0);
        assertThat(file.name()).isEqualTo("file");
        assertThat(file.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("name");
    }

    @Test
    void addPropertyDefinitionMutation_withACollidingNameInTheSameTrait_throws() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.TRAIT, traitId, "shared", "d2", PropertyType.INT, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void aPropertyAndAGroupOnTheSameParent_cannotShareAName() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        addProperty(ITEM, itemId, "dimensions", PropertyType.STRING);

        assertThatThrownBy(() -> createGroup(ITEM, itemId, "dimensions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void addPropertyDefinitionMutation_withACollidingNameInTheSameGroup_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(GROUP, dimensionsId, "length", PropertyType.INT);

        assertThatThrownBy(() -> addProperty(GROUP, dimensionsId, "length", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void sameNamedChild_canExistUnderTwoDifferentGroupsOnTheSameItem() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        UUID packagingId = createGroup(ITEM, itemId, "packagingDimensions");

        addProperty(GROUP, dimensionsId, "length", PropertyType.INT);
        addProperty(GROUP, packagingId, "length", PropertyType.INT);

        UUID dimensionsLengthId = findGroup(itemId, "dimensions").properties().get(0).id();
        UUID packagingLengthId = findGroup(itemId, "packagingDimensions").properties().get(0).id();
        assertThat(dimensionsLengthId).isNotEqualTo(packagingLengthId);
    }

    @Test
    void addPropertyDefinitionMutation_onAnItemType_cannotShareANameWithAnImplementedTrait() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        assertThatThrownBy(() -> addProperty(ITEM, itemId, traitName, PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collides");
    }

    @Test
    void implementTraitMutation_whoseNameMatchesAnExistingPropertyOnTheItem_throws() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        addProperty(ITEM, itemId, traitName, PropertyType.STRING);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collides");
    }

    @Test
    void addPropertyDefinitionMutation_onASupertype_isCheckedAgainstExistingSubtypeNames() {
        UUID superId = createItem("Super-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation("Sub-" + UUID.randomUUID(), "d", List.of(
                new CreatePropertyDefinitionMutation("clash", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), superId, false, null)));

        assertThatThrownBy(() -> addProperty(ITEM, superId, "clash", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collides");
    }

    @Test
    void movePropertyDefinitionMutation_intoAGroup_nestsItAndRemovesItFromItsOldParent() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(ITEM, itemId, "length", PropertyType.INT);
        UUID lengthId = findProperty(itemId, "length").id();

        schemaManager.applyMutations(List.of(new MovePropertyDefinitionMutation(lengthId, GROUP, dimensionsId)));

        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).doesNotContain("length");
        assertThat(findGroup(itemId, "dimensions").properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("length");
    }

    @Test
    void movePropertyDefinitionMutation_outOfAGroup_backToItemLevel() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(GROUP, dimensionsId, "length", PropertyType.INT);
        UUID lengthId = findGroup(itemId, "dimensions").properties().get(0).id();

        schemaManager.applyMutations(List.of(new MovePropertyDefinitionMutation(lengthId, ITEM, itemId)));

        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).contains("length");
        assertThat(findGroup(itemId, "dimensions").properties()).isEmpty();
    }

    @Test
    void movePropertyGroupDefinitionMutation_wouldCreateACycle_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID outerId = createGroup(ITEM, itemId, "outer");
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(GROUP, outerId, "inner", "d", List.of(), List.of())));
        UUID innerId = findGroup(itemId, "outer").groups().get(0).id();

        // inner is already nested inside outer -- moving outer to become a child of inner would
        // create a cycle (outer -> inner -> outer).
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new MovePropertyGroupDefinitionMutation(outerId, GROUP, innerId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void deletePropertyGroupDefinitionMutation_ofANonEmptyGroup_throws_andOfAnEmptyOne_succeeds() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(GROUP, dimensionsId, "length", PropertyType.INT);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeletePropertyGroupDefinitionMutation(dimensionsId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not empty");

        UUID lengthId = findGroup(itemId, "dimensions").properties().get(0).id();
        schemaManager.applyMutations(List.of(new DeletePropertyDefinitionMutation(lengthId)));
        schemaManager.applyMutations(List.of(new DeletePropertyGroupDefinitionMutation(dimensionsId)));

        assertThat(itemView(itemId).groups()).extracting(AdminPropertyGroupView::name).doesNotContain("dimensions");
    }

    private static final org.ntrloc.graph.db.partition.security.ResolvedPrincipal SUPERUSER =
            new org.ntrloc.graph.db.partition.security.ResolvedPrincipal(
                    UUID.randomUUID(), "test-superuser", "Test Superuser", null, java.util.Set.of(), true);
}
