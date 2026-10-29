package org.ntrloc.ui;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.schema.AllowedValue;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExistingControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminControlledListView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Editing an item type's Structure tab, end to end: edits are held until Save or Cancel, survive
 * browsing other item types, and are saved together. Changes made elsewhere while editing (here,
 * straight through SchemaManager, as another admin's save would be) show up live; one to a field
 * being edited has to be settled before saving. Every test edits its own item types.
 */
class SchemaEditingUiTest extends AbstractUiIntegrationTest {

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private ControlledListManager controlledListManager;

    private SchemaPage schema;
    private String bookType;
    private String authorType;

    @BeforeEach
    void createItemTypesAndSignIn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        bookType = "UiEditBook-" + suffix;
        authorType = "UiEditAuthor-" + suffix;
        UUID bookId = testData.createItemType(bookType, "title",
                List.of(property("title", PropertyType.STRING), property("pages", PropertyType.INT)), List.of()).id();
        UUID authorId = testData.createItemType(authorType, null, List.of(property("name", PropertyType.STRING)), List.of()).id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(bookId, "authors", "UI test fixture", 0, null),
                new CreatePerspectiveDefinitionMutation(authorId, "books", "UI test fixture", 0, null)))));

        openAdminUiAs(admin(), "/schema");
        schema = SchemaPage.of(driver).selectItemType(bookType);
    }

    @Test
    void editingOpensTheFieldsAndCancelPutsEverythingBack() {
        schema.edit();
        assertThat(schema.overviewEditable()).isTrue();
        assertThat(schema.topLevelAddButtons()).containsExactly("Add group", "Add property");
        assertThat(schema.cardActions("Links")).containsExactly("New link");

        schema.typeInto("Description", "Changed").toggleSwitch("Abstract type").toggleDeleteProperty("pages").toggleDeleteLink("authors");
        assertThat(schema.itemTypeOverview()).contains(entry("Description", "Changed"), entry("Abstract type", "Yes"));
        assertThat(schema.propertiesMarkedForDelete()).containsExactly("pages");
        assertThat(schema.linkMarkedForDelete("authors")).isTrue();
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.cancel();
        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.itemTypeOverview()).contains(entry("Description", "UI test fixture"), entry("Abstract type", "No"));
        assertThat(schema.topLevelAddButtons()).isEmpty();
        assertThat(schema.propertiesMarkedForDelete()).isEmpty();
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void aNewItemTypeIsEditedUntilSavedAndThenCreatedWithEverythingOnIt() {
        String name = "UiEditMagazine-" + UUID.randomUUID().toString().substring(0, 8);
        schema.newItemType(name);

        assertThat(schema.overviewEditable()).isTrue();
        assertThat(schema.itemTypeOverview()).containsEntry("Item type name", name);
        assertThat(schema.unsavedItemTypes()).contains(name);
        assertThat(schemaManager.getAdminSchema().items()).extracting(AdminItemDefinitionView::name).doesNotContain(name);

        schema.typeInto("Description", "Made in the UI")
                .newProperty().typeInPropertyEditor("Name", "issue").chooseInPropertyEditor("Type", "Integer").applyPropertyEditor();
        String forward = name + " to " + authorType;
        schema.newLink()
                .chooseInPropertyEditor("Links to", authorType)
                .typeInLinkEditor(forward, "Name", "contributors")
                .typeInLinkEditor(authorType + " back to " + name, "Name", "contributedTo")
                .applyPropertyEditor();
        schema.save();

        AdminItemDefinitionView saved = testData.itemType(name);
        assertThat(saved.description()).isEqualTo("Made in the UI");
        assertThat(saved.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("issue");
        assertThat(saved.links()).containsOnlyKeys("contributors");
        assertThat(testData.itemType(authorType).links()).containsKey("contributedTo");
        // Now the saved item type, under its real id.
        assertThat(driver.getCurrentUrl()).endsWith("/schema/item-types/" + saved.id());
        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void cancellingANewItemTypeDiscardsIt() {
        String name = "UiEditScratch-" + UUID.randomUUID().toString().substring(0, 8);
        schema.newItemType(name).newProperty().typeInPropertyEditor("Name", "note").applyPropertyEditor();

        schema.cancelDraft();

        assertThat(schema.itemTypeNames()).doesNotContain(name);
        assertThat(schema.unsavedItemTypes()).isEmpty();
        assertThat(driver.getCurrentUrl()).endsWith("/schema/item-types");
    }

    @Test
    void aNewItemTypeNeedsANameNoOtherItemTypeHas() {
        schema.startNewItemType();
        assertThat(schema.propertyEditorTitle()).isEqualTo("New item type");
        assertThat(schema.propertyEditorCanApply("Add")).isFalse();

        schema.typeInPropertyEditor("Name", bookType);
        assertThat(schema.propertyEditorCanApply("Add")).isFalse();
        assertThat(schema.propertyEditorError()).isEqualTo("There's already an item type named " + bookType + ".");

        schema.typeInPropertyEditor("Name", bookType + "-2");
        assertThat(schema.propertyEditorCanApply("Add")).isTrue();
        schema.cancelPropertyEditor();
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void deletingAnItemTypeAsksFirstAndTakesItsLinksWithIt() {
        assertThat(schema.edit().canDelete()).isFalse();
        schema.cancel();

        assertThat(schema.startDelete()).isEqualTo("Its links go with it: authors (to " + authorType + "). This can't be undone.");
        schema.cancelDelete();
        assertThat(schemaManager.getAdminSchema().items()).extracting(AdminItemDefinitionView::name).contains(bookType);

        schema.startDelete();
        schema.confirmDelete();

        assertThat(schemaManager.getAdminSchema().items()).extracting(AdminItemDefinitionView::name).doesNotContain(bookType);
        assertThat(Objects.requireNonNullElse(testData.itemType(authorType).links(), Map.of())).doesNotContainKey("books");
        assertThat(schema.itemTypeNames()).doesNotContain(bookType);
        assertThat(schema.unsavedItemTypes()).isEmpty();
        assertThat(driver.getCurrentUrl()).endsWith("/schema/item-types");
    }

    @Test
    void anItemTypeWithItemsCantBeDeleted() {
        testData.createItem(testData.itemType(bookType).id(), Map.of());

        schema.startDelete();
        String error = schema.confirmDeleteExpectingError();

        assertThat(error).contains("Cannot delete item type '" + bookType + "' because items of this type still exist");
        assertThat(schemaManager.getAdminSchema().items()).extracting(AdminItemDefinitionView::name).contains(bookType);
    }

    @Test
    void deletingATraitAsksFirst() {
        String name = "UiEditDoomedTrait-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createTrait(name, List.of(property("code", PropertyType.STRING)));
        assertThat(schema.openSection("Traits").selectTrait(name).startDelete()).isEqualTo("This can't be undone.");

        schema.confirmDelete();

        assertThat(schemaManager.getAdminSchema().traits()).extracting(AdminTraitDefinitionView::name).doesNotContain(name);
        assertThat(schema.traitNames()).doesNotContain(name);
        assertThat(driver.getCurrentUrl()).endsWith("/schema/traits");
    }

    @Test
    void aControlledListsEditsAreHeldUntilSaveAndThenAppliedTogether() {
        String name = "UiEditStatus-" + UUID.randomUUID().toString().substring(0, 8);
        UUID listId = controlledList(name, "OPEN", "Open", "SHUT", "Shut");
        schema.openSection("Controlled Lists").selectControlledList(name).edit();

        schema.typeInto("Name", name + "-renamed")
                .typeInValue(1, "Label", "Opened")
                .toggleRemoveValue("SHUT")
                .addValue().typeInValue(3, "Value", "HELD").typeInValue(3, "Label", "Held");
        assertThat(schema.unsavedControlledLists()).containsExactly(name);
        assertThat(labelsOf(listId)).containsExactly("Open", "Shut");

        schema.save();

        assertThat(controlledListManager.getListById(listId).orElseThrow().name()).isEqualTo(name + "-renamed");
        assertThat(labelsOf(listId)).containsExactly("Opened", "Held");
        assertThat(schema.cardTableRows("Values")).containsExactly("OPEN | Opened", "HELD | Held");
        assertThat(schema.unsavedControlledLists()).isEmpty();
    }

    @Test
    void aControlledListCantBeSavedWithValuesTheServerWouldRefuse() {
        String name = "UiEditRules-" + UUID.randomUUID().toString().substring(0, 8);
        controlledList(name, "OPEN", "Open");
        schema.openSection("Controlled Lists").selectControlledList(name).edit();

        // A new row isn't faulted for being empty before it's been filled in -- but can't be saved yet.
        schema.addValue();
        assertThat(schema.valueProblems()).isEmpty();
        assertThat(schema.saveEnabled()).isFalse();

        schema.typeInValue(2, "Value", "OPEN");
        assertThat(schema.valueProblems()).containsExactly("2 Value: Already in the list.");

        schema.typeInValue(2, "Value", "SHUT");
        assertThat(schema.valueProblems()).isEmpty();
        schema.leaveValues();
        assertThat(schema.valueProblems()).containsExactly("2 Label: Needs a label, as the other values have one.");
        assertThat(schema.saveEnabled()).isFalse();

        schema.typeInValue(2, "Label", "Open");
        assertThat(schema.valueProblems()).containsExactly("2 Label: Already used as a label.");

        schema.typeInValue(2, "Label", "Shut");
        assertThat(schema.valueProblems()).isEmpty();
        assertThat(schema.saveEnabled()).isTrue();
    }

    @Test
    void aNewControlledListIsEditedUntilSavedAndThenCreatedWithItsValues() {
        String name = "UiEditSizes-" + UUID.randomUUID().toString().substring(0, 8);
        schema.openSection("Controlled Lists").newControlledList(name);
        assertThat(schema.unsavedControlledLists()).contains(name);

        schema.chooseInOverview("Value type", "Integer")
                .addValue().typeInValue(1, "Value", "10")
                .addValue().typeInValue(2, "Value", "2");
        schema.save();

        var saved = controlledListManager.getAllLists().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
        assertThat(saved.valueType()).isEqualTo(PropertyType.INT);
        assertThat(controlledListManager.getValues(saved.id(), PropertyType.INT)).extracting(AllowedValue::value).containsExactly(10, 2);
        assertThat(driver.getCurrentUrl()).endsWith("/schema/controlled-lists/" + saved.id());
        assertThat(schema.unsavedControlledLists()).isEmpty();
    }

    @Test
    void deletingAControlledListAsksFirstAndDetachesTheProperties() {
        String name = "UiEditDoomedList-" + UUID.randomUUID().toString().substring(0, 8);
        UUID listId = controlledList(name, "A", "Aye");
        UUID titleId = UiTestData.propertyId(testData.itemType(bookType), "title");
        schemaManager.applyMutations(List.of(new SetPropertyControlledListMutation(titleId, new ExistingControlledListReference(listId))));
        assertThat(schema.openSection("Controlled Lists").selectControlledList(name).startDelete())
                .isEqualTo("Properties using it stop using it: " + bookType + " · title. This can't be undone.");

        schema.confirmDelete();

        assertThat(controlledListManager.getListById(listId)).isEmpty();
        assertThat(testData.itemType(bookType).properties()).filteredOn(p -> p.name().equals("title"))
                .extracting(AdminPropertyDefinitionView::controlledListId).containsOnlyNulls();
        assertThat(driver.getCurrentUrl()).endsWith("/schema/controlled-lists");
    }

    private UUID controlledList(String name, String... valuesAndLabels) {
        List<CreateControlledListMutation.Entry> entries = new ArrayList<>();
        for (int i = 0; i < valuesAndLabels.length; i += 2) {
            entries.add(new CreateControlledListMutation.Entry(valuesAndLabels[i], valuesAndLabels[i + 1]));
        }
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, name, PropertyType.STRING, entries)));
        return controlledListManager.getAllLists().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow().id();
    }

    private List<String> labelsOf(UUID listId) {
        return controlledListManager.getValues(listId, PropertyType.STRING).stream().map(AllowedValue::label).toList();
    }

    @Test
    void aNewTraitIsEditedUntilSavedAndThenCreatedWithEverythingOnIt() {
        String name = "UiEditTagged-" + UUID.randomUUID().toString().substring(0, 8);
        schema.openSection("Traits").newTrait(name);

        assertThat(schema.overviewEditable()).isTrue();
        assertThat(schema.itemTypeOverview()).containsEntry("Trait name", name);
        assertThat(schema.unsavedTraits()).contains(name);
        assertThat(schemaManager.getAdminSchema().traits()).extracting(AdminTraitDefinitionView::name).doesNotContain(name);

        schema.typeInto("Description", "Made in the UI")
                .newProperty().typeInPropertyEditor("Name", "tag").applyPropertyEditor();
        schema.newLink()
                .chooseInPropertyEditor("Links to", authorType)
                .typeInLinkEditor(name + " to " + authorType, "Name", "taggedBy")
                .typeInLinkEditor(authorType + " back to " + name, "Name", "tagged")
                .applyPropertyEditor();
        schema.save();

        AdminTraitDefinitionView saved = testData.trait(name);
        assertThat(saved.description()).isEqualTo("Made in the UI");
        assertThat(saved.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("tag");
        assertThat(saved.links()).containsOnlyKeys("taggedBy");
        assertThat(testData.itemType(authorType).links()).containsKey("tagged");
        // Now the saved trait, under its real id.
        assertThat(driver.getCurrentUrl()).endsWith("/schema/traits/" + saved.id());
        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.unsavedTraits()).isEmpty();
    }

    @Test
    void cancellingANewTraitDiscardsIt() {
        String name = "UiEditScratchTrait-" + UUID.randomUUID().toString().substring(0, 8);
        schema.openSection("Traits").newTrait(name).newProperty().typeInPropertyEditor("Name", "note").applyPropertyEditor();

        schema.cancelDraft();

        assertThat(schema.traitNames()).doesNotContain(name);
        assertThat(schema.unsavedTraits()).isEmpty();
        assertThat(driver.getCurrentUrl()).endsWith("/schema/traits");
    }

    @Test
    void aNewTraitNeedsANameNoOtherTraitHas() {
        String existing = "UiEditTakenTrait-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createTrait(existing, List.of());
        schema.openSection("Traits").startNewTrait();
        assertThat(schema.propertyEditorTitle()).isEqualTo("New trait");
        assertThat(schema.propertyEditorCanApply("Add")).isFalse();

        schema.typeInPropertyEditor("Name", existing);
        assertThat(schema.propertyEditorCanApply("Add")).isFalse();
        assertThat(schema.propertyEditorError()).isEqualTo("There's already a trait named " + existing + ".");

        schema.typeInPropertyEditor("Name", existing + "-2");
        assertThat(schema.propertyEditorCanApply("Add")).isTrue();
        schema.cancelPropertyEditor();
        assertThat(schema.unsavedTraits()).isEmpty();
    }

    @Test
    void aTraitsEditsAreHeldUntilSaveAndThenAppliedTogether() {
        String traitName = "UiEditTrait-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createTrait(traitName, List.of(property("code", PropertyType.STRING)));
        schema.openSection("Traits").selectTrait(traitName).edit();

        schema.typeInto("Description", "Edited in the UI")
                .toggleDeleteProperty("code")
                .newProperty().typeInPropertyEditor("Name", "region").applyPropertyEditor();
        assertThat(schema.unsavedTraits()).containsExactly(traitName);
        assertThat(testData.trait(traitName).description()).isEqualTo("UI test fixture");

        schema.save();

        AdminTraitDefinitionView saved = testData.trait(traitName);
        assertThat(saved.description()).isEqualTo("Edited in the UI");
        assertThat(saved.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("region");
        assertThat(schema.unsavedTraits()).isEmpty();
    }

    @Test
    void cancellingATraitsEditsPutsEverythingBack() {
        String traitName = "UiEditTrait-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createTrait(traitName, List.of(property("code", PropertyType.STRING)));
        schema.openSection("Traits").selectTrait(traitName).edit()
                .typeInto("Trait name", traitName + "-renamed")
                .toggleDeleteProperty("code");

        schema.cancel();

        assertThat(schema.itemTypeOverview()).containsEntry("Trait name", traitName);
        assertThat(schema.propertiesMarkedForDelete()).isEmpty();
        assertThat(schema.unsavedTraits()).isEmpty();
    }

    @Test
    void aTraitsNewLinkIsCreatedOnSave() {
        String traitName = "UiEditTrait-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createTrait(traitName, List.of());
        String forward = traitName + " to " + authorType;
        schema.openSection("Traits").selectTrait(traitName).edit().newLink()
                .chooseInPropertyEditor("Links to", authorType)
                .typeInLinkEditor(forward, "Name", "reviewers")
                .typeInLinkEditor(authorType + " back to " + traitName, "Name", "reviews")
                .applyPropertyEditor()
                .save();

        assertThat(testData.trait(traitName).links()).containsOnlyKeys("reviewers");
        assertThat(testData.itemType(authorType).links()).containsKey("reviews");
    }

    @Test
    void anEditUndoneByHandIsNoLongerAnUnsavedChange() {
        schema.edit().typeInto("Description", "Changed");
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.typeInto("Description", "UI test fixture");
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void editsSurviveBrowsingOtherItemTypes() {
        schema.edit().typeInto("Description", "Still here").toggleDeleteProperty("pages");

        schema.selectItemType(authorType);
        assertThat(schema.overviewEditable()).isFalse();
        schema.openSection("Traits").openSection("Item Types").selectItemType(bookType);

        assertThat(schema.overviewEditable()).isTrue();
        assertThat(schema.itemTypeOverview()).containsEntry("Description", "Still here");
        assertThat(schema.propertiesMarkedForDelete()).containsExactly("pages");
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);
    }

    @Test
    void savingAppliesEveryChangeTogether() {
        schema.edit()
                .typeInto("Description", "Saved from the UI")
                .toggleSwitch("Abstract type")
                .toggleDeleteProperty("pages")
                .toggleDeleteLink("authors")
                .save();

        AdminItemDefinitionView saved = testData.itemType(bookType);
        assertThat(saved.description()).isEqualTo("Saved from the UI");
        assertThat(saved.abstractType()).isTrue();
        assertThat(saved.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("title");
        assertThat(saved.links()).isNullOrEmpty();

        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.itemTypeOverview()).contains(entry("Description", "Saved from the UI"), entry("Abstract type", "Yes"));
        assertThat(schema.propertyNames()).containsExactly("title");
        assertThat(schema.cardText("Links")).isEqualTo("No links yet.");
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void onlyAnItemTypesOwnPropertiesCanBeDeletedHere() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID traitId = testData.createTrait("UiEditTrait-" + suffix, List.of(property("code", PropertyType.STRING))).id();
        UUID parentId = testData.createItemType("UiEditParent-" + suffix, null, List.of(property("inherited", PropertyType.STRING)), List.of()).id();
        String childType = "UiEditChild-" + suffix;
        testData.createItemType(childType, null, List.of(property("own", PropertyType.STRING)), List.of(), parentId, false, List.of(traitId));

        schema.selectItemType(childType).edit();

        assertThat(schema.propertyNames()).contains("own", "inherited", "code");
        assertThat(schema.deletableProperties()).containsExactly("own");
        assertThat(schema.editableProperties()).containsExactly("own");
        assertThat(schema.propertyRows()).contains(
                List.of("code (trait)", "UI test fixture", "String (optional)"),
                List.of("inherited (supertype)", "UI test fixture", "String (optional)"));
    }

    @Test
    void newGroupsAndEverythingInsideThemAreSavedTogether() {
        String listName = "UiEditFormats-" + UUID.randomUUID().toString().substring(0, 8);
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, listName, PropertyType.STRING, List.of(
                new CreateControlledListMutation.Entry("HARDBACK", "Hardback")))));

        schema.edit().newGroup();
        assertThat(schema.propertyEditorTitle()).isEqualTo("New group");
        assertThat(schema.propertyEditorCanApply()).isFalse();
        schema.typeInPropertyEditor("Name", "publication").applyPropertyEditor();
        assertThat(schema.groupActions("publication"))
                .containsExactly("Add group to publication", "Add property to publication", "Edit publication", "Delete publication");

        schema.addPropertyTo("publication").typeInPropertyEditor("Name", "year").chooseInPropertyEditor("Type", "Integer").applyPropertyEditor();
        schema.addGroupTo("publication").typeInPropertyEditor("Name", "edition").applyPropertyEditor();
        schema.addPropertyTo("edition")
                .typeInPropertyEditor("Name", "format")
                .chooseInPropertyEditor("Controlled list", listName)
                .applyPropertyEditor();
        assertThat(schema.propertyNames()).containsSubsequence("year", "format");

        schema.save();

        AdminPropertyGroupView publication = groupNamed(testData.itemType(bookType).groups(), "publication");
        assertThat(publication.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("year");
        AdminPropertyDefinitionView format = groupNamed(publication.groups(), "edition").properties().getFirst();
        assertThat(format.name()).isEqualTo("format");
        assertThat(format.controlledListId()).isEqualTo(controlledList(listName).id());
    }

    @Test
    void savedGroupsTakeNewPropertiesAndGroupsToo() {
        UUID bookId = testData.itemType(bookType).id();
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.ITEM, bookId, "details", "d",
                List.of(), List.of())));
        schema.awaitGroup("details").edit();
        assertThat(schema.groupActions("details"))
                .containsExactly("Add group to details", "Add property to details", "Edit details", "Delete details");

        schema.addPropertyTo("details").typeInPropertyEditor("Name", "language").applyPropertyEditor();
        schema.addGroupTo("details").typeInPropertyEditor("Name", "physical").applyPropertyEditor();
        schema.save();

        AdminPropertyGroupView details = groupNamed(testData.itemType(bookType).groups(), "details");
        assertThat(details.properties()).extracting(AdminPropertyDefinitionView::name).containsExactly("language");
        assertThat(details.groups()).extracting(AdminPropertyGroupView::name).containsExactly("physical");
    }

    @Test
    void aSavedGroupCanBeRenamed() {
        addSavedGroup("details", List.of(), List.of());
        schema.awaitGroup("details").edit().editGroup("details");
        assertThat(schema.propertyEditorTitle()).isEqualTo("Edit group details");

        schema.typeInPropertyEditor("Name", "specifics").applyPropertyEditor();
        assertThat(schema.editedGroups()).containsExactly("specifics");
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.save();

        assertThat(testData.itemType(bookType).groups()).extracting(AdminPropertyGroupView::name).containsExactly("specifics");
        assertThat(schema.editedGroups()).isEmpty();
    }

    @Test
    void deletingASavedGroupDeletesEverythingInsideIt() {
        addSavedGroup("details", List.of(property("language", PropertyType.STRING)), List.of(
                new CreatePropertyGroupDefinitionMutation("physical", null, List.of(property("weight", PropertyType.INT)), List.of())));
        schema.awaitGroup("details").edit();

        schema.toggleDeleteGroup("details");
        assertThat(schema.groupMarkedForDelete("details")).isTrue();
        // Everything inside goes with the group, so nothing in it has buttons of its own.
        assertThat(schema.groupActions("physical")).isEmpty();
        assertThat(schema.deletableProperties()).containsExactlyInAnyOrder("title", "pages");

        schema.toggleDeleteGroup("details");
        assertThat(schema.groupMarkedForDelete("details")).isFalse();
        assertThat(schema.unsavedItemTypes()).isEmpty();

        schema.toggleDeleteGroup("details").save();

        AdminItemDefinitionView saved = testData.itemType(bookType);
        assertThat(saved.groups()).isEmpty();
        assertThat(saved.properties()).extracting(AdminPropertyDefinitionView::name).containsExactlyInAnyOrder("title", "pages");
    }

    @Test
    void aLinksPropertiesAreEditedAndSavedWithTheItemType() {
        schema.edit().expandLink("authors").newLinkProperty("authors").typeInPropertyEditor("Name", "role").applyPropertyEditor();
        assertThat(SchemaPage.propertyRows(schema.linkCard("authors"))).containsExactly(List.of("role", "", "String (optional)"));
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.save();
        assertThat(linkProperties("authors")).extracting(AdminPropertyDefinitionView::name).containsExactly("role");

        schema.edit().editLinkProperty("authors", "role").typeInPropertyEditor("Description", "The author's role").applyPropertyEditor().save();
        assertThat(linkProperties("authors")).extracting(AdminPropertyDefinitionView::description).containsExactly("The author's role");
    }

    @Test
    void aLinksTwoDirectionsAreEditedAndSavedTogether() {
        String forward = bookType + " to " + authorType;
        String back = authorType + " back to " + bookType;
        schema.edit().editLink("authors");
        assertThat(schema.propertyEditorTitle()).isEqualTo("Edit link authors");
        assertThat(schema.linkEditorFields()).containsExactly(
                entry("Links to", authorType),
                entry(forward + ": Name", "authors"),
                entry(forward + ": Description", "UI test fixture"),
                entry(forward + ": Minimum", "0"),
                entry(forward + ": Maximum", ""),
                entry(back + ": Name", "books"),
                entry(back + ": Description", "UI test fixture"),
                entry(back + ": Minimum", "0"),
                entry(back + ": Maximum", ""));

        schema.typeInLinkEditor(forward, "Name", "writers").typeInLinkEditor(forward, "Maximum", "0");
        assertThat(schema.propertyEditorCanApply()).isFalse();
        assertThat(schema.propertyEditorError()).isEqualTo("This link's maximum must be blank (any) or at least 1.");

        schema.typeInLinkEditor(forward, "Maximum", "5").typeInLinkEditor(back, "Name", "works").applyPropertyEditor();
        assertThat(schema.linkDiagrams()).containsExactly(List.of("writers (5)", "works (0-any)", authorType));
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.save();

        assertThat(testData.itemType(bookType).links().get("writers").getFirst().maxCardinality()).isEqualTo(5);
        assertThat(testData.itemType(authorType).links()).containsOnlyKeys("works");
    }

    @Test
    void aNewLinkIsAddedOnSaveWithAnyPropertiesGivenIt() {
        String forward = bookType + " to " + authorType;
        String back = authorType + " back to " + bookType;
        schema.edit().newLink();
        assertThat(schema.propertyEditorTitle()).isEqualTo("New link");
        assertThat(schema.propertyEditorCanApply()).isFalse();

        schema.chooseInPropertyEditor("Links to", authorType)
                .typeInLinkEditor(forward, "Name", "editors")
                .typeInLinkEditor(back, "Name", "edited")
                .applyPropertyEditor();
        assertThat(schema.linkDiagrams()).contains(List.of("editors (0-any)", "edited (0-any)", authorType));
        // Open from the start, ready for its properties.
        assertThat(schema.linkExpanded("editors")).isTrue();

        schema.newLinkProperty("editors").typeInPropertyEditor("Name", "since").applyPropertyEditor();
        schema.save();

        assertThat(testData.itemType(authorType).links()).containsKey("edited");
        assertThat(linkProperties("editors")).extracting(AdminPropertyDefinitionView::name).containsExactly("since");
        // Saved, it starts folded like every other saved link.
        assertThat(schema.linkExpanded("editors")).isFalse();
    }

    @Test
    void droppingANewLinkDropsItAndAnythingAddedToIt() {
        String forward = bookType + " to " + authorType;
        String back = authorType + " back to " + bookType;
        schema.edit().newLink()
                .chooseInPropertyEditor("Links to", authorType)
                .typeInLinkEditor(forward, "Name", "editors")
                .typeInLinkEditor(back, "Name", "edited")
                .applyPropertyEditor();
        schema.newLinkProperty("editors").typeInPropertyEditor("Name", "since").applyPropertyEditor();

        schema.toggleDeleteLink("editors");

        assertThat(schema.linkDiagrams()).hasSize(1);
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void aLinkFromATraitSaysWhereItComesFromAndCannotBeChangedHere() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String traitName = "UiEditCiting-" + suffix;
        UUID traitId = testData.createTrait(traitName, List.of()).id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(traitId, "cites", "UI test fixture", 0, null),
                new CreatePerspectiveDefinitionMutation(testData.itemType(authorType).id(), "citedBy", "UI test fixture", 0, null)))));
        String essayType = "UiEditEssay-" + suffix;
        testData.createItemType(essayType, null, List.of(property("title", PropertyType.STRING)), List.of(), null, false, List.of(traitId));
        String link = "cites";

        schema.selectItemType(essayType).edit();

        assertThat(schema.linkSource(link)).isEqualTo("(trait)");
        assertThat(schema.linkActions(link)).isEmpty();
        schema.cancel().followLinkSource(link, traitName);
    }

    @Test
    void droppingANewGroupDropsEverythingInsideIt() {
        schema.edit().newGroup().typeInPropertyEditor("Name", "scratch").applyPropertyEditor();
        schema.addPropertyTo("scratch").typeInPropertyEditor("Name", "note").applyPropertyEditor();
        assertThat(schema.propertyNames()).contains("note");

        schema.toggleDeleteGroup("scratch");

        assertThat(schema.propertyNames()).doesNotContain("note");
        assertThat(schema.propertyRows()).doesNotContain(List.of("Group scratch"));
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void aPropertysEditsAreHeldUntilSaveAndThenAppliedTogether() {
        String listName = "UiEditGenres-" + UUID.randomUUID().toString().substring(0, 8);
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, listName, PropertyType.STRING, List.of(
                new CreateControlledListMutation.Entry("FICTION", "Fiction")))));
        schema.edit().editProperty("title");
        assertThat(schema.propertyEditorFields()).containsExactly(
                entry("Name", "title"),
                entry("Description", "UI test fixture"),
                entry("Type", "String"),
                entry("Controlled list", "None"),
                entry("Cardinality", "Single value"),
                entry("Usage", "Optional"));
        assertThat(schema.propertyEditorOptions("Controlled list")).contains("None", listName);

        schema.typeInPropertyEditor("Name", "heading")
                .typeInPropertyEditor("Description", "The book's heading")
                .chooseInPropertyEditor("Cardinality", "List")
                .chooseInPropertyEditor("Usage", "Required")
                .chooseInPropertyEditor("Controlled list", listName)
                .applyPropertyEditor();

        assertThat(schema.propertyRows()).contains(List.of("heading", "The book's heading", "String List (required)"));
        assertThat(schema.editedProperties()).containsExactly("heading");
        assertThat(propertyNamed(bookType, "title")).isNotNull();

        schema.save();

        AdminPropertyDefinitionView saved = propertyNamed(bookType, "heading");
        assertThat(saved.description()).isEqualTo("The book's heading");
        assertThat(saved.cardinality()).isEqualTo(PropertyCardinality.LIST);
        assertThat(saved.usage()).isEqualTo(PropertyUsage.REQUIRED);
        assertThat(saved.controlledListId()).isEqualTo(controlledList(listName).id());
        assertThat(schema.editedProperties()).isEmpty();
    }

    @Test
    void aNewPropertyNeedsANameAndIsAddedOnSave() {
        schema.edit().newProperty();
        assertThat(schema.propertyEditorTitle()).isEqualTo("New property");
        assertThat(schema.propertyEditorFields()).containsExactly(
                entry("Name", ""),
                entry("Description", ""),
                entry("Type", "String"),
                entry("Controlled list", "None"),
                entry("Cardinality", "Single value"),
                entry("Usage", "Optional"));
        assertThat(schema.propertyEditorCanApply()).isFalse();
        assertThat(schema.propertyEditorError()).isEmpty();

        schema.typeInPropertyEditor("Name", "x").typeInPropertyEditor("Name", " ");
        assertThat(schema.propertyEditorCanApply()).isFalse();
        assertThat(schema.propertyEditorError()).isEqualTo("A property needs a name.");

        schema.typeInPropertyEditor("Name", "isbn")
                .chooseInPropertyEditor("Type", "Long")
                .chooseInPropertyEditor("Cardinality", "Set")
                .applyPropertyEditor();
        assertThat(schema.propertyRows()).contains(List.of("isbn", "", "Long Set (optional)"));
        assertThat(schema.editedProperties()).containsExactly("isbn");
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.save();

        AdminPropertyDefinitionView isbn = propertyNamed(bookType, "isbn");
        assertThat(isbn.type()).isEqualTo(PropertyType.LONG);
        assertThat(isbn.cardinality()).isEqualTo(PropertyCardinality.SET);
        assertThat(schema.editedProperties()).isEmpty();
    }

    @Test
    void aNewPropertyCanComeWithAControlledList() {
        String listName = "UiEditFormats-" + UUID.randomUUID().toString().substring(0, 8);
        schemaManager.applyMutations(List.of(new CreateControlledListMutation(null, listName, PropertyType.STRING, List.of(
                new CreateControlledListMutation.Entry("HARDBACK", "Hardback")))));

        schema.edit().newProperty()
                .typeInPropertyEditor("Name", "format")
                .chooseInPropertyEditor("Controlled list", listName)
                .applyPropertyEditor()
                .save();

        assertThat(propertyNamed(bookType, "format").controlledListId()).isEqualTo(controlledList(listName).id());
    }

    @Test
    void aNewPropertyCanBeEditedOrDroppedBeforeSaving() {
        schema.edit().newProperty().typeInPropertyEditor("Name", "draft").applyPropertyEditor();

        schema.editProperty("draft");
        assertThat(schema.propertyEditorTitle()).isEqualTo("New property");
        schema.typeInPropertyEditor("Name", "renamed").applyPropertyEditor();
        assertThat(schema.propertyNames()).contains("renamed").doesNotContain("draft");

        schema.toggleDeleteProperty("renamed");
        assertThat(schema.propertyNames()).doesNotContain("renamed");
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void cancellingThePropertyEditorChangesNothing() {
        schema.edit().editProperty("pages").typeInPropertyEditor("Description", "Changed");
        assertThat(schema.propertyEditorOptions("Cardinality")).containsExactly("Single value", "List", "Set");

        schema.cancelPropertyEditor();

        assertThat(schema.editedProperties()).isEmpty();
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void aPropertySomeoneElseChangesWhileItIsBeingEditedMustBeSettledBeforeSaving() {
        schema.edit().editProperty("title").typeInPropertyEditor("Description", "Mine").applyPropertyEditor();

        AdminPropertyDefinitionView title = propertyNamed(bookType, "title");
        schemaManager.applyMutations(List.of(new UpdatePropertyDefinitionMutation(title.id(), "title", "Theirs",
                title.type(), title.cardinality(), title.usage(), title.facetable())));
        schema.awaitConflict();
        assertThat(schema.conflicts()).containsExactly("Property title was changed by someone else");
        assertThat(schema.saveEnabled()).isFalse();

        schema.useTheirs("Property title");

        assertThat(schema.conflicts()).isEmpty();
        assertThat(schema.propertyRows()).contains(List.of("title", "Theirs", "String (optional)"));
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void aFieldSomeoneElseChangesWhileItIsBeingEditedMustBeSettledBeforeSaving() {
        schema.edit().typeInto("Description", "Mine");

        changeElsewhere(bookType, "Theirs", "title");
        schema.awaitConflict();
        assertThat(schema.conflicts()).containsExactly("Description is now Theirs");
        assertThat(schema.saveEnabled()).isFalse();

        schema.keepMine("Description");
        assertThat(schema.conflicts()).isEmpty();
        schema.save();

        assertThat(testData.itemType(bookType).description()).isEqualTo("Mine");
    }

    @Test
    void takingTheirValueSettlesAConflictAndDropsTheEdit() {
        schema.edit().typeInto("Description", "Mine");

        changeElsewhere(bookType, "Theirs", "title");
        schema.awaitConflict().useTheirs("Description");

        assertThat(schema.conflicts()).isEmpty();
        assertThat(schema.itemTypeOverview()).containsEntry("Description", "Theirs");
        assertThat(schema.unsavedItemTypes()).isEmpty();
    }

    @Test
    void changesSomeoneElseMakesToOtherFieldsAreKeptOnSave() {
        schema.edit().typeInto("Description", "Mine");

        changeElsewhere(bookType, "UI test fixture", "pages");
        schema.awaitOverviewEntry("Display label", "pages");
        assertThat(schema.conflicts()).isEmpty();
        schema.save();

        AdminItemDefinitionView saved = testData.itemType(bookType);
        assertThat(saved.description()).isEqualTo("Mine");
        assertThat(saved.displayLabelPattern()).isEqualTo("pages");
    }

    // Another admin's save of a new group on the book type.
    private void addSavedGroup(String name, List<CreatePropertyDefinitionMutation> properties,
                               List<CreatePropertyGroupDefinitionMutation> groups) {
        schemaManager.applyMutations(List.of(new AddPropertyGroupDefinitionMutation(PropertyContainerKind.ITEM,
                testData.itemType(bookType).id(), name, null, properties, groups)));
    }

    private List<AdminPropertyDefinitionView> linkProperties(String perspectiveName) {
        UUID linkId = testData.itemType(bookType).links().get(perspectiveName).getFirst().linkId();
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkId))
                .findFirst()
                .orElseThrow()
                .properties();
    }

    private AdminPropertyDefinitionView propertyNamed(String itemTypeName, String propertyName) {
        return testData.itemType(itemTypeName).properties().stream()
                .filter(property -> property.name().equals(propertyName))
                .findFirst()
                .orElse(null);
    }

    private static AdminPropertyGroupView groupNamed(List<AdminPropertyGroupView> groups, String name) {
        return groups.stream().filter(group -> group.name().equals(name)).findFirst().orElseThrow();
    }

    private AdminControlledListView controlledList(String name) {
        return schemaManager.getAdminSchema().controlledLists().stream()
                .filter(list -> list.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    // Another admin's save of the item type's own fields.
    private void changeElsewhere(String itemTypeName, String description, String displayLabelPattern) {
        AdminItemDefinitionView itemType = testData.itemType(itemTypeName);
        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemType.id(), itemType.name(), description,
                itemType.supertypeId(), itemType.abstractType(), displayLabelPattern)));
    }
}
