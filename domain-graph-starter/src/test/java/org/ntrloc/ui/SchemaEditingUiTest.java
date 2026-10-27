package org.ntrloc.ui;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
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

        openAdminUiAs(createLocalAdmin(), "/schema");
        schema = SchemaPage.of(driver).selectItemType(bookType);
    }

    @Test
    void editingOpensTheFieldsAndCancelPutsEverythingBack() {
        schema.edit();
        assertThat(schema.overviewEditable()).isTrue();
        assertThat(schema.cardActions("Properties")).containsExactly("New property");
        assertThat(schema.cardActions("Links")).containsExactly("New link");

        schema.typeInto("Description", "Changed").toggleSwitch("Abstract type").toggleDeleteProperty("pages").toggleDeleteLink("authors");
        assertThat(schema.itemTypeOverview()).contains(entry("Description", "Changed"), entry("Abstract type", "Yes"));
        assertThat(schema.propertiesMarkedForDelete()).containsExactly("pages");
        assertThat(schema.linkMarkedForDelete("authors")).isTrue();
        assertThat(schema.unsavedItemTypes()).containsExactly(bookType);

        schema.cancel();
        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.itemTypeOverview()).contains(entry("Description", "UI test fixture"), entry("Abstract type", "No"));
        assertThat(schema.cardActions("Properties")).isEmpty();
        assertThat(schema.propertiesMarkedForDelete()).isEmpty();
        assertThat(schema.unsavedItemTypes()).isEmpty();
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
        assertThat(propertyNames()).containsExactly("title");
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

        assertThat(propertyNames()).contains("own", "inherited", "code");
        assertThat(schema.deletableProperties()).containsExactly("own");
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

    // Another admin's save of the item type's own fields.
    private void changeElsewhere(String itemTypeName, String description, String displayLabelPattern) {
        AdminItemDefinitionView itemType = testData.itemType(itemTypeName);
        schemaManager.applyMutations(List.of(new UpdateItemDefinitionMutation(itemType.id(), itemType.name(), description,
                itemType.supertypeId(), itemType.abstractType(), displayLabelPattern)));
    }

    private List<String> propertyNames() {
        return schema.propertyRows().stream().map(row -> row.get(1)).filter(name -> !name.isEmpty()).toList();
    }
}
