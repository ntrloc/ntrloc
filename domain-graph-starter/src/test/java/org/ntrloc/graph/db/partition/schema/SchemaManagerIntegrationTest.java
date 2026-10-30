package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExistingControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ImplementTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.MovePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.NewControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.RemoveTraitMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetStateMachineLayoutMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private SchemaRepository schemaRepo;

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
        // A trait's contributions sit on the item under their own names, tagged with where they came from.
        assertThat(adminItem.properties()).filteredOn(p -> p.name().equals("inheritedProp")).singleElement()
                .satisfies(p -> assertThat(p.definedIn().entityName()).isEqualTo(traitName));
        assertThat(adminItem.groups()).extracting(g -> g.name()).doesNotContain(traitName);
        assertThat(adminItem.links()).containsKey("reviewers");
        assertThat(adminItem.links().get("reviewers").get(0).targets())
                .extracting(t -> t.kind()).contains("item");

        var reviewerAdminItem = findItem(reviewerName);
        assertThat(reviewerAdminItem.links().get("reviewedTrait").get(0).targets())
                .extracting(t -> t.kind()).contains("trait");

        var calculatedItem = schemaManager.getSchema(ADMIN).items().stream()
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

    // --- Batches are all-or-nothing ---

    // Read straight from the database: the cached admin schema isn't rebuilt after a failed batch,
    // so it would show the old state whether or not the batch was rolled back.
    private Set<String> storedItemNames() {
        return schemaRepo.getAllItems().stream().map(SchemaRepository.ItemRow::name).collect(Collectors.toSet());
    }

    private static CreateLinkDefinitionMutation linkToAnUnknownItemType() {
        return new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(UUID.randomUUID(), "ghost", "d", 0, null)));
    }

    @Test
    void aBatchThatFailsPartwayThrough_leavesEarlierChangesInItUnapplied() {
        String name = "Item-" + UUID.randomUUID();
        UUID itemId = createItem(name);
        String newName = "Renamed-" + UUID.randomUUID();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(itemId, newName, "d", null, false, null),
                linkToAnUnknownItemType())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedItemNames()).contains(name).doesNotContain(newName);
    }

    @Test
    void aBatchThatFailsAfterCreatingAnItemType_leavesNoItemTypeBehind() {
        String name = "Item-" + UUID.randomUUID();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation(name, "d", List.of(), null, false, null),
                linkToAnUnknownItemType())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedItemNames()).doesNotContain(name);
    }

    // --- Expecting an item type's fields to be unchanged ---

    @Test
    void anExpectationTheItemTypeStillMeets_letsTheRestOfTheBatchApply() {
        String name = "Item-" + UUID.randomUUID();
        UUID itemId = createItem(name);
        String newName = "Renamed-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(
                new ExpectItemDefinitionMutation(itemId, name, "d", null, false, null),
                new UpdateItemDefinitionMutation(itemId, newName, "d", null, false, null)));

        assertThat(findItem(newName).id()).isEqualTo(itemId);
    }

    @Test
    void updateTraitDefinitionMutation_afterAMatchingExpectation_renamesAndRedescribesTheTrait() {
        String name = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(name, "d", List.of())));
        UUID traitId = findTrait(name).id();
        String newName = "Renamed-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(
                new ExpectTraitDefinitionMutation(traitId, name, "d"),
                new UpdateTraitDefinitionMutation(traitId, newName, "changed")));

        assertThat(findTrait(newName).id()).isEqualTo(traitId);
        assertThat(findTrait(newName).description()).isEqualTo("changed");
    }

    @Test
    void aTraitExpectationAboutAFieldSomeoneElseChanged_rejectsTheWholeBatch() {
        String name = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(name, "d", List.of())));
        UUID traitId = findTrait(name).id();
        schemaManager.applyMutations(List.of(new UpdateTraitDefinitionMutation(traitId, name, "changed elsewhere")));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new ExpectTraitDefinitionMutation(traitId, name, "d"),
                new UpdateTraitDefinitionMutation(traitId, "Renamed-" + UUID.randomUUID(), "d"))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("changed by someone else");
        assertThat(findTrait(name).description()).isEqualTo("changed elsewhere");
    }

    @Test
    void aTraitExpectationAboutADeletedTrait_isAConflict() {
        String name = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(name, "d", List.of())));
        UUID traitId = findTrait(name).id();
        schemaManager.applyMutations(List.of(new DeleteTraitDefinitionMutation(traitId)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ExpectTraitDefinitionMutation(traitId, name, null))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void anExpectationAboutAFieldSomeoneElseChanged_rejectsTheWholeBatch() {
        String name = "Item-" + UUID.randomUUID();
        UUID itemId = createItem(name);
        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemId, name, "changed elsewhere", null, false, null)));
        String newName = "Renamed-" + UUID.randomUUID();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new ExpectItemDefinitionMutation(itemId, name, "d", null, false, null),
                new UpdateItemDefinitionMutation(itemId, newName, "d", null, false, null))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("changed by someone else");

        assertThat(storedItemNames()).contains(name).doesNotContain(newName);
    }

    @Test
    void deletingAnItemType_deletesItsLinksToo() {
        String authorName = "Author-" + UUID.randomUUID();
        UUID authorId = createItem(authorName);
        UUID bookId = createItem("Book-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(bookId, "writtenBy", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(authorId, "wrote", "d", 0, null)))));

        schemaManager.applyMutations(List.of(new DeleteItemDefinitionMutation(bookId)));

        assertThat(Objects.requireNonNullElse(findItem(authorName).links(), Map.of())).doesNotContainKey("wrote");
    }

    @Test
    void deletingAnItemTypeOthersInheritFrom_throwsNamingThem() {
        String vehicleName = "Vehicle-" + UUID.randomUUID();
        UUID vehicleId = createItem(vehicleName);
        String carName = "Car-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeleteItemDefinitionMutation(vehicleId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot delete item type '" + vehicleName + "' because " + carName + " inherits from it");
        assertThat(storedItemNames()).contains(vehicleName);
    }

    @Test
    void deletingATrait_deletesItsLinksToo() {
        String authorName = "Author-" + UUID.randomUUID();
        UUID authorId = createItem(authorName);
        String traitName = "Authored-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(traitId, "writtenBy", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(authorId, "wrote", "d", 0, null)))));

        schemaManager.applyMutations(List.of(new DeleteTraitDefinitionMutation(traitId)));

        assertThat(schemaManager.getAdminSchema().traits()).extracting(t -> t.name()).doesNotContain(traitName);
        assertThat(Objects.requireNonNullElse(findItem(authorName).links(), Map.of())).doesNotContainKey("wrote");
    }

    @Test
    void anExpectationAboutADeletedItemType_isAConflict() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new DeleteItemDefinitionMutation(itemId)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new ExpectItemDefinitionMutation(itemId, "anything", null, null, false, null))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void anExpectation_treatsBlankAndMissingTextAsTheSame() {
        String name = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(name, null, List.of(), null, false, null)));
        UUID itemId = findItem(name).id();

        schemaManager.applyMutations(List.of(new ExpectItemDefinitionMutation(itemId, name, "", null, false, "")));
    }

    @Test
    void createItemPropertyDefinitionMutation_withACollidingName_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "shared", "d2", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createLinkPropertyDefinitionMutation_withACollidingName_throws() {
        UUID linkId = createBareLink();
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.LINK, linkId, "shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.LINK, linkId, "shared", "d2", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
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
                propId, "renamed", "updated", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, false)));
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
                .hasMessageContaining("is already a link perspective");
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

        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, name, PropertyType.STRING,
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
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, name, PropertyType.STRING, List.of())));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateControlledListMutation(null, name.toUpperCase(), PropertyType.STRING, List.of()))))
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

        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, new ExistingControlledListReference(list.id()))));
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

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, new ExistingControlledListReference(list.id())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void setPropertyControlledListMutation_withAValueTypeMismatch_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "str");
        var intList = controlledListManager.createList("List-" + UUID.randomUUID(), PropertyType.INTEGER);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, new ExistingControlledListReference(intList.id())))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createThenSetPropertyControlledList_inSeparateBatches_surfacesInGetAdminSchema() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "status");
        String listName = "List-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, listName, PropertyType.STRING,
                List.of(new CreateControlledListMutation.Entry("Open", "Open")))));
        UUID listId = controlledListManager.getAllLists().stream()
                .filter(l -> l.name().equals(listName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(propId, new ExistingControlledListReference(listId))));

        var schema = schemaManager.getAdminSchema();
        var prop = schema.items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.id().equals(propId)).findFirst().orElseThrow();
        assertThat(prop.controlledListId()).isEqualTo(listId);

        var listView = schema.controlledLists().stream().filter(l -> l.id().equals(listId)).findFirst().orElseThrow();
        assertThat(listView.valueCount()).isEqualTo(1);
        assertThat(listView.usedBy()).extracting(u -> u.propertyId(), u -> u.ownerId(), u -> u.ownerKind())
                .contains(tuple(propId, itemId, PropertyContainerKind.ITEM));
    }

    private UUID createStringList(String name) {
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, name, PropertyType.STRING,
                List.of(new CreateControlledListMutation.Entry("Open", "Open")))));
        return controlledListManager.getAllLists().stream()
                .filter(l -> l.name().equals(name)).findFirst().orElseThrow().id();
    }

    @Test
    void aControlledListExpectationThatStillHolds_letsTheUpdateThrough() {
        String name = "List-" + UUID.randomUUID();
        UUID listId = createStringList(name);

        schemaManager.applyMutations(List.of(
                new ExpectControlledListMutation(listId, name, List.of(new UpdateControlledListMutation.Entry("Open", "Open"))),
                new UpdateControlledListMutation(listId, null, List.of(
                        new UpdateControlledListMutation.Entry("Open", "Open"), new UpdateControlledListMutation.Entry("Closed", "Closed")))));

        assertThat(controlledListManager.getValues(listId, PropertyType.STRING)).extracting(AllowedValue::value).containsExactly("Open", "Closed");
    }

    @Test
    void aControlledListExpectationAboutValuesSomeoneElseChanged_rejectsTheWholeBatch() {
        String name = "List-" + UUID.randomUUID();
        UUID listId = createStringList(name);
        schemaManager.applyMutations(List.of(new UpdateControlledListMutation(listId, null,
                List.of(new UpdateControlledListMutation.Entry("Open", "Opened elsewhere")))));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new ExpectControlledListMutation(listId, name, List.of(new UpdateControlledListMutation.Entry("Open", "Open"))),
                new UpdateControlledListMutation(listId, "Renamed-" + UUID.randomUUID(), null))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("changed by someone else");
        assertThat(controlledListManager.getListById(listId).orElseThrow().name()).isEqualTo(name);
    }

    @Test
    void aControlledListExpectationAboutADeletedList_isAConflict() {
        String name = "List-" + UUID.randomUUID();
        UUID listId = createStringList(name);
        schemaManager.applyMutations(List.of(new DeleteControlledListMutation(listId)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ExpectControlledListMutation(listId, name, null))))
                .isInstanceOf(SchemaConflictException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void creatingAnIntegerControlledList_storesItsValuesAsNumbers() {
        String name = "List-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, name, PropertyType.INTEGER,
                List.of(new CreateControlledListMutation.Entry("10", null), new CreateControlledListMutation.Entry("2", null)))));
        UUID listId = controlledListManager.getAllLists().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow().id();

        assertThat(controlledListManager.getValues(listId, PropertyType.INTEGER)).extracting(AllowedValue::value).containsExactly(10L, 2L);
    }

    @Test
    void addPropertyDefinitionMutation_withAControlledList_attachesItToTheNewProperty() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID listId = createStringList("List-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId, "status", "d",
                PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, listId)));

        var status = schemaManager.getAdminSchema().items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("status")).findFirst().orElseThrow();
        assertThat(status.controlledListId()).isEqualTo(listId);
    }

    @Test
    void addPropertyGroupDefinitionMutation_attachesControlledListsToPropertiesInsideIt() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID listId = createStringList("List-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.ITEM, itemId, "details", "d",
                List.of(), List.of(new CreatePropertyGroupDefinitionMutation("inner", "d", List.of(new CreatePropertyDefinitionMutation(
                        "status", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, listId)), List.of())))));

        var details = schemaManager.getAdminSchema().items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .groups().stream().filter(g -> g.name().equals("details")).findFirst().orElseThrow();
        var status = details.groups().getFirst().properties().getFirst();
        assertThat(status.name()).isEqualTo("status");
        assertThat(status.controlledListId()).isEqualTo(listId);
    }

    @Test
    void addPropertyDefinitionMutation_withAControlledListOfAnotherType_addsNothing() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID listId = createStringList("List-" + UUID.randomUUID());

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, itemId,
                "count", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false, listId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is STRING but property 'count' is INTEGER");

        assertThat(schemaRepo.findChildNames(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.ITEM, itemId)))
                .doesNotContain("count");
    }

    // CreateControlledListMutation's own refId, resolved by a NewControlledListReference later in
    // the *same* batch -- the whole point of adding it: create-and-attach as one real mutation
    // batch (what SchemaAdminController's own PUT endpoint now does) instead of the two-call,
    // look-it-up-by-name-in-between dance the test above still demonstrates for the "list already
    // has a real id" case.
    @Test
    void createControlledListMutation_withARefId_resolvesInTheSameBatch_viaNewControlledListReference() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "status");
        String listName = "List-" + UUID.randomUUID();

        List<ControlledListMutationResult> results = schemaManager.applyMutations(List.of(
                new CreateControlledListMutation("new-list", listName, PropertyType.STRING,
                        List.of(new CreateControlledListMutation.Entry("Open", "Open"))),
                new SetPropertyControlledListMutation(propId, new NewControlledListReference("new-list"))));

        assertThat(results).hasSize(1);
        UUID listId = results.get(0).listId();
        assertThat(results.get(0).refId()).isEqualTo("new-list");

        assertThat(controlledListManager.getListForProperty(propId))
                .hasValueSatisfying(l -> assertThat(l.id()).isEqualTo(listId));
    }

    @Test
    void setPropertyControlledListMutation_withAnUnknownRefId_throws() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID propId = createStringProp(itemId, "status");

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new SetPropertyControlledListMutation(propId, new NewControlledListReference("never-created")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown controlled list refId");
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
    void setStateMachineLayoutMutation_storesTheLayoutAsGiven_andNullClearsIt() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", null)));
        var machine = onlyStateMachine(itemId);
        assertThat(machine.layout()).isNull();

        var layout = JsonMapper.builder().build().readTree("""
                {"positions": {"%s": {"x": 40, "y": -24}}, "transitions": {}}
                """.formatted(machine.states().get(0).id()));
        schemaManager.applyMutations(List.of(new SetStateMachineLayoutMutation(machine.id(), layout)));
        assertThat(onlyStateMachine(itemId).layout()).isEqualTo(layout);

        schemaManager.applyMutations(List.of(new SetStateMachineLayoutMutation(machine.id(), null)));
        assertThat(onlyStateMachine(itemId).layout()).isNull();
    }

    @Test
    void aStateCreatedWithARefId_canBePlacedByItInTheSameBatch() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", null)));
        UUID machineId = onlyStateMachine(itemId).id();

        var layout = JsonMapper.builder().build().readTree("""
                {"positions": {"new-state-1": {"x": 160, "y": 80}}, "transitions": {}}
                """);
        schemaManager.applyMutations(List.of(
                new CreateStateMutation(machineId, "Drafted", null, null, null, "new-state-1"),
                new SetStateMachineLayoutMutation(machineId, layout)));

        var machine = onlyStateMachine(itemId);
        UUID draftedId = machine.states().stream().filter(s -> s.name().equals("Drafted")).findFirst().orElseThrow().id();
        assertThat(machine.layout().get("positions").propertyNames()).containsExactly(draftedId.toString());
        assertThat(machine.layout().get("positions").get(draftedId.toString()).get("x").asInt()).isEqualTo(160);
    }

    // --- Moving a transition between states ---

    // A machine with states A, B and C, and one transition, A -> B, named "go".
    private record MovingTransition(UUID itemId, UUID machineId, UUID a, UUID b, UUID c, UUID transitionId) {}

    private MovingTransition aMachineWithATransitionFromAToB() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", null)));
        UUID machineId = onlyStateMachine(itemId).id();
        schemaManager.applyMutations(List.of(
                new CreateStateMutation(machineId, "A", null, null, null),
                new CreateStateMutation(machineId, "B", null, null, null),
                new CreateStateMutation(machineId, "C", null, null, null)));
        UUID a = stateNamed(itemId, "A").id();
        UUID b = stateNamed(itemId, "B").id();
        schemaManager.applyMutations(List.of(new CreateTransitionMutation(a, b, "go", null, null, null)));
        return new MovingTransition(itemId, machineId, a, b, stateNamed(itemId, "C").id(), stateNamed(itemId, "A").transitions().get(0).id());
    }

    private AdminStateView stateNamed(UUID itemId, String name) {
        return onlyStateMachine(itemId).states().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void movingATransitionsEnds_keepsTheSameTransition() {
        var m = aMachineWithATransitionFromAToB();

        schemaManager.applyMutations(List.of(
                new UpdateTransitionMutation(m.transitionId(), "go", null, null, null, m.c().toString(), m.a().toString())));

        assertThat(stateNamed(m.itemId(), "A").transitions()).isEmpty();
        var moved = stateNamed(m.itemId(), "C").transitions();
        assertThat(moved).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(m.transitionId());
            assertThat(t.toStateId()).isEqualTo(m.a());
        });
    }

    @Test
    void aTransitionCanBeMovedToAStateCreatedInTheSameBatch() {
        var m = aMachineWithATransitionFromAToB();

        schemaManager.applyMutations(List.of(
                new CreateStateMutation(m.machineId(), "D", null, null, null, "new-state-1"),
                new UpdateTransitionMutation(m.transitionId(), "go", null, null, null, null, "new-state-1")));

        assertThat(stateNamed(m.itemId(), "A").transitions().get(0).toStateId()).isEqualTo(stateNamed(m.itemId(), "D").id());
    }

    @Test
    void movingATransition_keepsToTheStructuralRules() {
        var m = aMachineWithATransitionFromAToB();
        UUID start = onlyStateMachine(m.itemId()).states().stream().filter(s -> s.kind().equals("START")).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new CreateTransitionMutation(m.a(), m.c(), "other", null, null, null)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateTransitionMutation(m.transitionId(), "go", null, null, null, null, start.toString()))))
                .hasMessageContaining("START pseudostate cannot have incoming transitions");
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateTransitionMutation(m.transitionId(), "go", null, null, null, null, m.c().toString()))))
                .hasMessageContaining("There's already a transition from 'A' to 'C'");
        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateTransitionMutation(m.transitionId(), "go", null, null, null, null, m.a().toString()))))
                .hasMessageContaining("can't lead from a state back to itself");
        assertThat(stateNamed(m.itemId(), "A").transitions()).extracting(t -> t.toStateId()).containsExactlyInAnyOrder(m.b(), m.c());
    }

    @Test
    void aNewTransitionToANewState_isCreatedAndDrawnInOneBatch() {
        var m = aMachineWithATransitionFromAToB();
        var layout = JsonMapper.builder().build().readTree("""
                {"positions": {}, "transitions": {"new-transition-1": {"to": {"side": "top", "at": 0.5}}}}
                """);

        schemaManager.applyMutations(List.of(
                new CreateStateMutation(m.machineId(), "D", null, null, null, "new-state-1"),
                new CreateTransitionMutation(m.b(), null, "onward", null, null, null, null, "new-state-1", "new-transition-1"),
                new SetStateMachineLayoutMutation(m.machineId(), layout)));

        var onward = stateNamed(m.itemId(), "B").transitions().get(0);
        assertThat(onward.toStateId()).isEqualTo(stateNamed(m.itemId(), "D").id());
        assertThat(onlyStateMachine(m.itemId()).layout().get("transitions").propertyNames()).containsExactly(onward.id().toString());
    }

    private AdminStateMachineView onlyStateMachine(UUID itemId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0);
    }

    @Test
    void updateStateMutation_andDeleteStateMutation() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemId, "machine", "d")));
        UUID machineId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .stateMachines().get(0).id();
        schemaManager.applyMutations(List.of(new CreateStateMutation(machineId, "original", "d", null, null)));
        UUID stateId = normalStateNamed(itemId, "original").id();

        schemaManager.applyMutations(List.of(
                new UpdateStateMutation(stateId, "renamed", "updated", "entry-proc", "exit-proc")));
        var afterUpdate = normalStateNamed(itemId, "renamed");
        assertThat(afterUpdate.name()).isEqualTo("renamed");
        assertThat(afterUpdate.kind()).isEqualTo("NORMAL");

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

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateStateMutation(machineId, "__start__", "d", null, null))))
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
                new CreateStateMutation(machineId, "s1", "d", null, null),
                new CreateStateMutation(machineId, "s2", "d", null, null)));
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
                new CreateStateMutation(machineId, "from", "d", null, null),
                new CreateStateMutation(machineId, "to", "d", null, null)));
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
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
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

        var calculatedCar = schemaManager.getSchema(ADMIN).items().stream()
                .filter(i -> i.name().equals(carName)).findFirst().orElseThrow();
        assertThat(calculatedCar.properties()).extracting(p -> p.name()).contains("wheels", "doors");
        assertThat(calculatedCar.supertypeId()).isEqualTo(vehicleId);
    }

    @Test
    void multiLevelSupertypeChain_accumulatesPropertiesFromEveryAncestor() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        String sportsCarName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(sportsCarName, "d", List.of(
                new CreatePropertyDefinitionMutation("topSpeed", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                carId, false, null)));

        var sportsCar = findItem(sportsCarName);
        assertThat(sportsCar.properties()).extracting(p -> p.name()).contains("wheels", "doors", "topSpeed");
    }

    @Test
    void createItemDefinitionMutation_reDeclaringAPropertyNameFromItsSupertype_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "Item-" + UUID.randomUUID(), "d", List.of(
                        new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("is already a property or group");
    }

    @Test
    void createItemDefinitionMutation_reDeclaringAPropertyNameFromAMultiHopSupertypeChain_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                "Item-" + UUID.randomUUID(), "d", List.of(
                        new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                carId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels");
    }

    @Test
    void createItemPropertyDefinitionMutation_reDeclaringAPropertyNameFromItsSupertype_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, carId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("is already a property or group");
    }

    @Test
    void updateItemDefinitionMutation_reParentingIntoACollisionWithItsOwnProperty_throws() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String boatName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(boatName, "d", List.of(
                new CreatePropertyDefinitionMutation("wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                null, false, null)));
        UUID boatId = findItem(boatName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new UpdateItemDefinitionMutation(boatId, boatName, "d", vehicleId, false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wheels")
                .hasMessageContaining("is already a property or group");
    }

    @Test
    void itemWithBothATraitAndASupertype_inheritsFromBoth() {
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, vehicleId, "wheels", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("insured", "d", PropertyType.BOOLEAN, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();

        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(
                new CreatePropertyDefinitionMutation("doors", "d", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)),
                vehicleId, false, null)));
        UUID carId = findItem(carName).id();
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(carId, traitId)));

        var car = findItem(carName);
        assertThat(car.properties()).extracting(p -> p.name()).containsExactlyInAnyOrder("doors", "wheels", "insured");
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
    void createItemDefinitionMutation_withARefId_canBeLinkedToLaterInTheSameBatch() {
        String authorName = "Author-" + UUID.randomUUID();
        UUID authorId = createItem(authorName);
        String bookName = "Book-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation(bookName, "d", List.of(), null, false, null, List.of(), List.of(), "book"),
                new CreateLinkDefinitionMutation(List.of(), List.of(
                        new CreatePerspectiveDefinitionMutation(null, "writtenBy", "d", 0, null, "book"),
                        new CreatePerspectiveDefinitionMutation(authorId, "wrote", "d", 0, null)))));

        assertThat(findItem(bookName).links()).containsOnlyKeys("writtenBy");
        assertThat(findItem(bookName).links().get("writtenBy").getFirst().targets()).extracting(t -> t.name())
                .containsExactly(authorName);
    }

    @Test
    void createTraitDefinitionMutation_withARefId_canBeLinkedToLaterInTheSameBatch() {
        String authorName = "Author-" + UUID.randomUUID();
        UUID authorId = createItem(authorName);
        String traitName = "Authored-" + UUID.randomUUID();

        schemaManager.applyMutations(List.of(
                new CreateTraitDefinitionMutation(traitName, "d", List.of(), List.of(), "authored"),
                new CreateLinkDefinitionMutation(List.of(), List.of(
                        new CreatePerspectiveDefinitionMutation(null, "writtenBy", "d", 0, null, "authored"),
                        new CreatePerspectiveDefinitionMutation(authorId, "wrote", "d", 0, null)))));

        assertThat(findTrait(traitName).links()).containsOnlyKeys("writtenBy");
        assertThat(findTrait(traitName).links().get("writtenBy").getFirst().targets()).extracting(t -> t.name())
                .containsExactly(authorName);
    }

    @Test
    void createLinkDefinitionMutation_withAnUnknownItemRefId_throwsAndCreatesNothing() {
        UUID authorId = createItem("Author-" + UUID.randomUUID());
        String bookName = "Book-" + UUID.randomUUID();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation(bookName, "d", List.of(), null, false, null, List.of(), List.of(), "book"),
                new CreateLinkDefinitionMutation(List.of(), List.of(
                        new CreatePerspectiveDefinitionMutation(null, "writtenBy", "d", 0, null, "no-such-ref"),
                        new CreatePerspectiveDefinitionMutation(authorId, "wrote", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown refId: no-such-ref");
        assertThat(storedItemNames()).doesNotContain(bookName);
    }

    @Test
    void createLinkDefinitionMutation_withBothAnItemIdAndAnItemRefId_throws() {
        UUID authorId = createItem("Author-" + UUID.randomUUID());

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(authorId, "self", "d", 0, null, "book"),
                new CreatePerspectiveDefinitionMutation(authorId, "selfBack", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of itemId and itemRefId");
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

        addProperty(GROUP, dimensionsId, "length", PropertyType.INTEGER);

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
    void aTraitsContributions_appearOnImplementingItemsUnderTheirOwnNames() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("name", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        assertThat(itemView(itemId).properties()).filteredOn(p -> p.name().equals("name")).singleElement()
                .satisfies(p -> assertThat(p.definedIn().entityName()).isEqualTo(traitName));
        assertThat(itemView(itemId).groups()).isEmpty();
        assertThat(itemView(itemId).sortableFields()).extracting(f -> f.name()).contains("name").doesNotContain(traitName + ".name");
    }

    @Test
    void aTraitOwnedGroup_appearsAtTheImplementingItemsTopLevel() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.TRAIT, traitId, "file", "d",
                List.of(new CreatePropertyDefinitionMutation("name", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), List.of())));
        UUID itemId = createItem("Item-" + UUID.randomUUID());

        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        var file = findGroup(itemId, "file");
        assertThat(file.definedIn().entityName()).isEqualTo(traitName);
        assertThat(file.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("name");
    }

    @Test
    void addPropertyDefinitionMutation_withACollidingNameInTheSameTrait_throws() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation("shared", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        UUID traitId = findTrait(traitName).id();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.TRAIT, traitId, "shared", "d2", PropertyType.INTEGER, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false))))
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
        addProperty(GROUP, dimensionsId, "length", PropertyType.INTEGER);

        assertThatThrownBy(() -> addProperty(GROUP, dimensionsId, "length", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void sameNamedChild_canExistUnderTwoDifferentGroupsOnTheSameItem() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        UUID packagingId = createGroup(ITEM, itemId, "packagingDimensions");

        addProperty(GROUP, dimensionsId, "length", PropertyType.INTEGER);
        addProperty(GROUP, packagingId, "length", PropertyType.INTEGER);

        UUID dimensionsLengthId = findGroup(itemId, "dimensions").properties().get(0).id();
        UUID packagingLengthId = findGroup(itemId, "packagingDimensions").properties().get(0).id();
        assertThat(dimensionsLengthId).isNotEqualTo(packagingLengthId);
    }

    @Test
    void addPropertyDefinitionMutation_onAnItemType_cannotShareANameWithAnImplementedTraitsProperty() {
        UUID traitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        assertThatThrownBy(() -> addProperty(ITEM, itemId, "code", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'code' is already a property or group");
    }

    @Test
    void anItemTypeMayHaveAPropertyNamedForATraitItImplements() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        addProperty(ITEM, itemId, traitName, PropertyType.STRING);

        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).contains(traitName);
    }

    @Test
    void addPropertyDefinitionMutation_onATrait_isCheckedAgainstEveryImplementingItemType() {
        UUID traitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        addProperty(ITEM, itemId, "region", PropertyType.STRING);
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId)));

        assertThatThrownBy(() -> addProperty(PropertyContainerKind.TRAIT, traitId, "region", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'region' is already a property or group on item type");
    }

    @Test
    void implementTraitMutation_whenAnotherImplementedTraitHasAPropertyOfTheSameName_throws() {
        UUID firstTraitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID secondTraitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, firstTraitId)));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, secondTraitId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'code' is already a property or group");
    }

    @Test
    void aTraitImplementedByBothAnItemTypeAndItsSupertype_contributesItsPropertiesOnce() {
        UUID traitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID vehicleId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new ImplementTraitMutation(vehicleId, traitId)));
        String carName = "Item-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(carName, "d", List.of(), vehicleId, false, null)));
        UUID carId = findItem(carName).id();

        schemaManager.applyMutations(List.of(new ImplementTraitMutation(carId, traitId)));

        assertThat(findItem(carName).properties()).extracting(p -> p.name()).containsExactly("code");
    }

    @Test
    void createLinkDefinitionMutation_onATrait_isCheckedAgainstEveryImplementingItemTypesPerspectives() {
        String traitName = "Trait-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of())));
        UUID traitId = findTrait(traitName).id();
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID otherId = createItem("Item-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(
                new ImplementTraitMutation(itemId, traitId),
                new CreateLinkDefinitionMutation(List.of(), List.of(
                        new CreatePerspectiveDefinitionMutation(itemId, "related", "d", 0, null),
                        new CreatePerspectiveDefinitionMutation(otherId, "relatedFrom", "d", 0, null)))));

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(traitId, "related", "d", 0, null),
                new CreatePerspectiveDefinitionMutation(otherId, "relatedTrait", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'related' is already a link perspective");
    }

    private UUID createTraitWithProperty(String traitName, String propertyName) {
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(traitName, "d", List.of(
                new CreatePropertyDefinitionMutation(propertyName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)))));
        return findTrait(traitName).id();
    }

    @Test
    void implementTraitMutation_whosePropertyMatchesAnExistingPropertyOnTheItem_throws() {
        UUID traitId = createTraitWithProperty("Trait-" + UUID.randomUUID(), "code");
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        addProperty(ITEM, itemId, "code", PropertyType.STRING);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new ImplementTraitMutation(itemId, traitId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'code' is already a property or group");
    }

    @Test
    void addPropertyDefinitionMutation_onASupertype_isCheckedAgainstExistingSubtypeNames() {
        UUID superId = createItem("Super-" + UUID.randomUUID());
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation("Sub-" + UUID.randomUUID(), "d", List.of(
                new CreatePropertyDefinitionMutation("clash", "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)), superId, false, null)));

        assertThatThrownBy(() -> addProperty(ITEM, superId, "clash", PropertyType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is already a property or group");
    }

    @Test
    void movePropertyDefinitionMutation_intoAGroup_nestsItAndRemovesItFromItsOldParent() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(ITEM, itemId, "length", PropertyType.INTEGER);
        UUID lengthId = findProperty(itemId, "length").id();

        schemaManager.applyMutations(List.of(new MovePropertyDefinitionMutation(lengthId, GROUP, dimensionsId)));

        assertThat(itemView(itemId).properties()).extracting(AdminPropertyDefinitionView::name).doesNotContain("length");
        assertThat(findGroup(itemId, "dimensions").properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("length");
    }

    @Test
    void movePropertyDefinitionMutation_outOfAGroup_backToItemLevel() {
        UUID itemId = createItem("Item-" + UUID.randomUUID());
        UUID dimensionsId = createGroup(ITEM, itemId, "dimensions");
        addProperty(GROUP, dimensionsId, "length", PropertyType.INTEGER);
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
        addProperty(GROUP, dimensionsId, "length", PropertyType.INTEGER);

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new DeletePropertyGroupDefinitionMutation(dimensionsId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not empty");

        UUID lengthId = findGroup(itemId, "dimensions").properties().get(0).id();
        schemaManager.applyMutations(List.of(new DeletePropertyDefinitionMutation(lengthId)));
        schemaManager.applyMutations(List.of(new DeletePropertyGroupDefinitionMutation(dimensionsId)));

        assertThat(itemView(itemId).groups()).extracting(AdminPropertyGroupView::name).doesNotContain("dimensions");
    }

    private static final org.ntrloc.graph.db.partition.security.NtrlocPrincipal ADMIN =
            org.ntrloc.graph.db.partition.security.TestPrincipals.of(UUID.randomUUID(), "Test Admin", null, java.util.Set.of(), true);

    // --- Schema-view filtering by item-type:read permission (SchemaManager.getSchema) ---
    //
    // A user not added to the default "everyone" group (which auto-grants item-type:read on every
    // item type, existing or future -- see DefaultUserGroupInitializer.onItemTypeCreated) starts
    // with zero item-type visibility; granting read on only one of two fresh types proves the
    // filtering is genuinely per-type, not just "sees nothing."
    @Test
    void getSchema_forAUserLackingItemTypeReadOnOneType_hidesThatTypeButShowsAGrantedOne() {
        String visibleTypeName = "Item-" + UUID.randomUUID();
        String hiddenTypeName = "Item-" + UUID.randomUUID();
        UUID visibleTypeId = createItem(visibleTypeName);
        UUID hiddenTypeId = createItem(hiddenTypeName);

        var user = securityRepo.createUser("Restricted", "sme-" + UUID.randomUUID() + "@example.com", false);
        var principal = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), false);
        authRepo.grantItemType(visibleTypeId, "USER", user.id(), PermissionService.ITEM_TYPE_READ);

        var visibleNames = schemaManager.getSchema(principal).items().stream().map(i -> i.name()).toList();
        assertThat(visibleNames).contains(visibleTypeName);
        assertThat(visibleNames).doesNotContain(hiddenTypeName);
    }
}
