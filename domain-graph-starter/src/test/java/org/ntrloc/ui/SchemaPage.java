package org.ntrloc.ui;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The admin UI's Schema view: the sidebar accordion (item types, traits, controlled lists) and the
 * selected item type's or trait's detail tabs -- for an item type, the Structure tab's Overview,
 * Properties and Links cards, and editing them. Text is read with textContent, as in ItemPanePage.
 */
class SchemaPage {

    private static final By CONTENT = By.cssSelector("schema-view-content");

    private final WebDriver driver;

    private SchemaPage(WebDriver driver) {
        this.driver = driver;
    }

    /** Waits for the schema to load. */
    static SchemaPage of(WebDriver driver) {
        SchemaPage page = new SchemaPage(driver);
        page.await().until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("schema-view")));
        return page;
    }

    /** Opens an accordion section, and waits for its opening animation to finish. */
    SchemaPage openSection(String label) {
        driver.findElement(By.xpath("//schema-view-nav//button[contains(@class,'accordion-tab')][.='" + label + "']")).click();
        await().until(d -> "true".equals(d.findElement(By.xpath(
                "//schema-view-nav//button[contains(@class,'accordion-tab')][.='" + label + "']")).getDomAttribute("aria-expanded")));
        await().until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("return document.getAnimations().length === 0")));
        return this;
    }

    List<String> itemTypeNames() {
        return texts(driver.findElements(By.cssSelector(".schema-item-types-list-item")));
    }

    /** The item types the sidebar marks as having unsaved changes. */
    List<String> unsavedItemTypes() {
        return texts(driver.findElements(By.xpath(
                "//button[contains(@class,'schema-item-types-list-item')][span[contains(@class,'schema-item-types-unsaved')]]")));
    }

    /** Opens the New Item Type dialog from the sidebar. */
    SchemaPage startNewItemType() {
        return openEditor(driver.findElement(By.xpath("//schema-item-types-view/button[.='New Item Type']")));
    }

    /** Adds a new item type through the New Item Type dialog, and waits for it to open. */
    SchemaPage newItemType(String name) {
        startNewItemType().typeInPropertyEditor("Name", name).closePropertyEditor("Add");
        return awaitHeading(name);
    }

    List<String> traitNames() {
        return texts(driver.findElements(By.cssSelector(".schema-traits-list-item")));
    }

    List<String> controlledListNames() {
        return texts(driver.findElements(By.cssSelector(".schema-controlled-lists-list li")));
    }

    /** Selects an item type, waiting for it to be listed if it was only just created. */
    SchemaPage selectItemType(String name) {
        await().until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(@class,'schema-item-types-list-item')][.='" + name + "']"))).click();
        return awaitHeading(name);
    }

    SchemaPage selectTrait(String name) {
        driver.findElement(By.xpath("//button[contains(@class,'schema-traits-list-item')][.='" + name + "']")).click();
        return awaitHeading(name);
    }

    /** The highlighted entry in whichever sidebar list is open. */
    String selectedListEntry() {
        return text(driver.findElement(By.cssSelector("schema-view-nav button[aria-current='true']")));
    }

    SchemaPage awaitHeading(String name) {
        await().until(d -> d.findElements(By.cssSelector("schema-view-content h2")).stream().anyMatch(h -> text(h).equals(name)));
        return this;
    }

    String heading() {
        return settled(() -> text(content().findElement(By.tagName("h2"))));
    }

    /** The whole detail area's text, e.g. its placeholder when nothing is selected. */
    String contentText() {
        return text(content());
    }

    List<String> tabs() {
        return texts(content().findElements(By.cssSelector("tab-view-tabs button[role='tab']")));
    }

    SchemaPage openTab(String label) {
        WebElement tab = content().findElement(By.xpath(".//tab-view-tabs/button[@role='tab'][.='" + label + "']"));
        tab.click();
        await().until(d -> "true".equals(tab.getDomAttribute("aria-selected")));
        return this;
    }

    String activeTab() {
        return text(content().findElement(By.cssSelector("tab-view-tabs button[aria-selected='true']")));
    }

    String tabText() {
        return text(content().findElement(By.cssSelector("tab-view-panel")));
    }

    /** The titles of the Structure tab's cards, in order. */
    List<String> structureCards() {
        return texts(content().findElements(By.cssSelector("collapsible-card .collapsible-card-title")));
    }

    SchemaPage toggleCard(String title) {
        card(title).findElement(By.cssSelector(".collapsible-card-toggle")).click();
        return this;
    }

    boolean cardExpanded(String title) {
        return "true".equals(card(title).findElement(By.cssSelector(".collapsible-card-toggle")).getDomAttribute("aria-expanded"));
    }

    /** A Structure card's content, e.g. its placeholder when it's empty. */
    String cardText(String title) {
        return settled(() -> text(card(title).findElement(By.tagName("collapsible-card-body"))));
    }

    /** The buttons in a Structure card's title bar by accessible name, e.g. "New link" while editing. */
    List<String> cardActions(String title) {
        return card(title).findElements(By.cssSelector("collapsible-card-actions button")).stream()
                .map(button -> button.getDomAttribute("aria-label") != null ? button.getDomAttribute("aria-label") : text(button))
                .toList();
    }

    /** Whether the Properties card's filter is in its title bar. */
    boolean propertyFilterShown() {
        return !card("Properties").findElements(By.cssSelector("collapsible-card-actions input[name='property-filter']")).isEmpty();
    }

    /**
     * An item type's Overview card, label to value. Switches read as Yes/No; text fields read the
     * same whether or not the item type is being edited.
     */
    Map<String, String> itemTypeOverview() {
        return settled(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            for (WebElement field : card("Overview").findElements(By.cssSelector("item-type-overview > *"))) {
                fields.put(text(field.findElement(By.cssSelector(".input-control-name"))), fieldValue(field));
            }
            return fields;
        });
    }

    private static String fieldValue(WebElement field) {
        List<WebElement> inputs = field.findElements(By.cssSelector("input.input-control-field"));
        if (!inputs.isEmpty()) {
            return inputs.getFirst().getDomProperty("value");
        }
        List<WebElement> switches = field.findElements(By.cssSelector("[role='switch']"));
        if (!switches.isEmpty()) {
            return "true".equals(switches.getFirst().getDomAttribute("aria-checked")) ? "Yes" : "No";
        }
        return text(field.findElement(By.cssSelector(".input-control-value, .popover-button-value, .popover-button-trigger-label")));
    }

    /** Waits for an Overview field to show a value, e.g. one someone else just saved. */
    SchemaPage awaitOverviewEntry(String fieldLabel, String value) {
        await().until(d -> value.equals(itemTypeOverview().get(fieldLabel)));
        return this;
    }

    /** Whether the Overview's fields can be changed right now (inputs rather than plain text). */
    boolean overviewEditable() {
        return !card("Overview").findElements(By.cssSelector("input.input-control-field")).isEmpty();
    }

    SchemaPage edit() {
        footerButton("Edit").click();
        await().until(d -> !footerButtons("Save").isEmpty());
        return this;
    }

    SchemaPage cancel() {
        footerButton("Cancel").click();
        await().until(d -> !footerButtons("Edit").isEmpty());
        return this;
    }

    /** Cancels a new item type, which closes it: nothing is left to show. */
    SchemaPage cancelNewItemType() {
        footerButton("Cancel").click();
        await().until(d -> d.findElements(By.cssSelector("schema-view-content h2")).isEmpty());
        return this;
    }

    /** Saves, and waits until the item type is back out of editing. */
    SchemaPage save() {
        footerButton("Save").click();
        await().until(d -> !footerButtons("Edit").isEmpty());
        return this;
    }

    boolean saveEnabled() {
        return footerButton("Save").isEnabled();
    }

    /** Replaces an Overview text field's value. */
    SchemaPage typeInto(String fieldLabel, String text) {
        WebElement input = card("Overview").findElement(By.xpath(
                ".//input-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    SchemaPage toggleSwitch(String fieldLabel) {
        card("Overview").findElement(By.xpath(
                ".//switch-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//button[@role='switch']")).click();
        return this;
    }

    /** Marks a property for delete, or unmarks it -- its row's trash/undo button. */
    SchemaPage toggleDeleteProperty(String name) {
        card("Properties").findElement(By.xpath(
                ".//td[contains(@class,'property-tree-actions')]//button[@aria-label='Delete " + name + "' or @aria-label='Keep " + name + "']")).click();
        return this;
    }

    /** Opens the property editor from a property's row. */
    SchemaPage editProperty(String name) {
        card("Properties").findElement(By.cssSelector("td.property-tree-actions button[aria-label='Edit " + name + "']")).click();
        await().until(d -> !d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    /** The add buttons at the top of the Properties card, by accessible name. */
    List<String> topLevelAddButtons() {
        return card("Properties").findElements(By.cssSelector(".property-tree-toolbar button")).stream()
                .map(button -> button.getDomAttribute("aria-label"))
                .toList();
    }

    /** Opens the property editor for a new top-level property. */
    SchemaPage newProperty() {
        return openEditor(card("Properties").findElement(By.cssSelector(".property-tree-toolbar button[aria-label='Add property']")));
    }

    /** Opens the group editor for a new top-level group. */
    SchemaPage newGroup() {
        return openEditor(card("Properties").findElement(By.cssSelector(".property-tree-toolbar button[aria-label='Add group']")));
    }

    /** Opens the property editor for a new property at the top level of a link's (unfolded) properties. */
    SchemaPage newLinkProperty(String perspectiveName) {
        return openEditor(linkCard(perspectiveName).findElement(By.cssSelector(".property-tree-toolbar button[aria-label='Add property']")));
    }

    /** Opens the property editor from a property's row in a link's properties. */
    SchemaPage editLinkProperty(String perspectiveName, String name) {
        return openEditor(linkCard(perspectiveName).findElement(By.cssSelector("td.property-tree-actions button[aria-label='Edit " + name + "']")));
    }

    SchemaPage addPropertyTo(String groupName) {
        return openEditor(card("Properties").findElement(By.cssSelector("button[aria-label='Add property to " + groupName + "']")));
    }

    SchemaPage addGroupTo(String groupName) {
        return openEditor(card("Properties").findElement(By.cssSelector("button[aria-label='Add group to " + groupName + "']")));
    }

    /** Waits for a group to appear in the Properties card, e.g. one someone else just saved. */
    SchemaPage awaitGroup(String groupName) {
        await().until(d -> !card("Properties").findElements(group(groupName)).isEmpty());
        return this;
    }

    /** The add, edit, and delete buttons on a group's header in the Properties card, by accessible name. */
    List<String> groupActions(String groupName) {
        return card("Properties").findElement(group(groupName)).findElements(By.cssSelector(
                        ":scope > header .property-tree-add button, :scope > header .property-tree-group-actions button")).stream()
                .map(button -> button.getDomAttribute("aria-label"))
                .toList();
    }

    /** Opens the group editor from a group's header. */
    SchemaPage editGroup(String groupName) {
        return openEditor(card("Properties").findElement(group(groupName))
                .findElement(By.cssSelector(":scope > header button[aria-label='Edit " + groupName + "']")));
    }

    /** Marks a group, with everything in it, for delete, or unmarks it -- its header's trash/undo button. */
    SchemaPage toggleDeleteGroup(String groupName) {
        card("Properties").findElement(group(groupName)).findElement(By.xpath(
                "./header//button[@aria-label='Delete " + groupName + "' or @aria-label='Keep " + groupName + "']")).click();
        return this;
    }

    boolean groupMarkedForDelete(String groupName) {
        return card("Properties").findElement(group(groupName)).getDomAttribute("data-marked-for-delete") != null;
    }

    /** Groups whose name has edits not yet saved. */
    List<String> editedGroups() {
        return texts(card("Properties").findElements(By.cssSelector("section.property-tree-group[data-edited] > header .property-tree-group-name")));
    }

    private static By group(String groupName) {
        return By.cssSelector("section.property-tree-group[aria-label='Group " + groupName + "']");
    }

    // Scrolled to the middle first: the editing footer stays pinned over the bottom of the view,
    // where a click would otherwise scroll a button to, and lands on the footer instead.
    private SchemaPage openEditor(WebElement button) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'})", button);
        button.click();
        await().until(d -> !d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    String propertyEditorTitle() {
        return text(dialog().findElement(By.className("dialog-title")));
    }

    boolean propertyEditorCanApply() {
        return propertyEditorCanApply("Apply");
    }

    /** Whether the open dialog's button with this label (Apply, Add) can be clicked. */
    boolean propertyEditorCanApply(String button) {
        return dialog().findElement(By.xpath(".//dialog-actions//button[.='" + button + "']")).isEnabled();
    }

    /** The property editor's validation message, or empty when there's none. */
    String propertyEditorError() {
        return texts(dialog().findElements(By.className("property-edit-error"))).stream().findFirst().orElse("");
    }

    /** The properties whose rows offer an edit button. */
    List<String> editableProperties() {
        return card("Properties").findElements(By.cssSelector("td.property-tree-actions button[aria-label^='Edit ']")).stream()
                .map(button -> button.getDomAttribute("aria-label").substring("Edit ".length()))
                .toList();
    }

    /** Properties with edits not yet saved. */
    List<String> editedProperties() {
        return card("Properties").findElements(By.cssSelector("tr[data-edited] td:nth-child(1)")).stream()
                .map(SchemaPage::text)
                .toList();
    }

    /** The open property editor's fields, label to value. */
    Map<String, String> propertyEditorFields() {
        return settled(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            for (WebElement field : dialog().findElements(By.cssSelector("property-edit-fields > *"))) {
                fields.put(text(field.findElement(By.cssSelector(".input-control-name"))), fieldValue(field));
            }
            return fields;
        });
    }

    SchemaPage typeInPropertyEditor(String fieldLabel, String text) {
        WebElement input = dialog().findElement(By.xpath(
                ".//input-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    /** Picks one of a choice field's options in the property editor. */
    SchemaPage chooseInPropertyEditor(String fieldLabel, String option) {
        WebElement field = dialog().findElement(By.xpath(
                ".//popover-button[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]"));
        field.findElement(By.cssSelector(".popover-button-trigger")).click();
        field.findElement(By.xpath(".//button[contains(@class,'option-list-option')][.='" + option + "']")).click();
        return this;
    }

    /** The options a choice field in the property editor offers, closing it again afterwards. */
    List<String> propertyEditorOptions(String fieldLabel) {
        WebElement field = dialog().findElement(By.xpath(
                ".//popover-button[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]"));
        WebElement trigger = field.findElement(By.cssSelector(".popover-button-trigger"));
        trigger.click();
        List<String> options = texts(field.findElements(By.cssSelector(".option-list-option")));
        trigger.click();
        return options;
    }

    SchemaPage applyPropertyEditor() {
        return closePropertyEditor("Apply");
    }

    SchemaPage cancelPropertyEditor() {
        return closePropertyEditor("Cancel");
    }

    SchemaPage closePropertyEditor(String button) {
        dialog().findElement(By.xpath(".//dialog-actions//button[.='" + button + "']")).click();
        await().until(d -> d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    private WebElement dialog() {
        return driver.findElement(By.tagName("dialog-view"));
    }

    /** The properties whose rows offer a delete button: the ones this item type declares itself. */
    List<String> deletableProperties() {
        return card("Properties").findElements(By.cssSelector("td.property-tree-actions button:not([aria-label^='Edit '])")).stream()
                .map(button -> button.getDomAttribute("aria-label").replaceFirst("^(Delete|Keep) ", ""))
                .toList();
    }

    List<String> propertiesMarkedForDelete() {
        return card("Properties").findElements(By.cssSelector("tr[data-marked-for-delete] td:nth-child(1)")).stream()
                .map(SchemaPage::text)
                .toList();
    }

    SchemaPage toggleDeleteLink(String perspectiveName) {
        linkCard(perspectiveName).findElement(By.xpath(
                ".//button[@aria-label='Delete " + perspectiveName + "' or @aria-label='Keep " + perspectiveName + "']")).click();
        return this;
    }

    boolean linkMarkedForDelete(String perspectiveName) {
        return !linkCard(perspectiveName).findElements(By.cssSelector("button[aria-label='Keep " + perspectiveName + "']")).isEmpty();
    }

    /** Fields someone else changed while they were being edited here, e.g. "Description is now “x”". */
    List<String> conflicts() {
        return texts(content().findElements(By.cssSelector("item-type-conflicts li span")));
    }

    SchemaPage awaitConflict() {
        await().until(d -> !conflicts().isEmpty());
        return this;
    }

    SchemaPage keepMine(String fieldLabel) {
        return resolveConflict(fieldLabel, "Keep mine");
    }

    SchemaPage useTheirs(String fieldLabel) {
        return resolveConflict(fieldLabel, "Use theirs");
    }

    private SchemaPage resolveConflict(String fieldLabel, String choice) {
        content().findElement(By.xpath(".//item-type-conflicts//li[@data-field='" + fieldLabel + "']//button[.='" + choice + "']")).click();
        return this;
    }

    /** The trait's Overview tab's rows, label to value. */
    Map<String, String> overview() {
        return settled(() -> {
            Map<String, String> rows = new LinkedHashMap<>();
            for (WebElement row : content().findElements(By.cssSelector(".entity-overview-row"))) {
                rows.put(text(row.findElement(By.tagName("dt"))), text(row.findElement(By.tagName("dd"))));
            }
            return rows;
        });
    }

    /** Follows one of a trait's Overview links to an item type that implements it. */
    SchemaPage followOverviewLink(String name) {
        settled(() -> {
            content().findElement(By.xpath(".//button[contains(@class,'entity-overview-link')][.='" + name + "']")).click();
            return true;
        });
        return awaitHeading(name);
    }

    /**
     * The property tree in reading order, for an item type's Properties card or a trait's Properties
     * tab: each property as its Name, Description, and Type; each group as "Group " and its path from
     * the top level, e.g. "Group publication > publisher", with " (trait)" for a trait's; and any
     * "No properties yet." or "No matching properties." message.
     */
    List<List<String>> propertyRows() {
        return propertyRows(propertyTable());
    }

    /** Just the properties' names, in reading order. */
    List<String> propertyNames() {
        return texts(propertyTable().findElements(By.cssSelector("table.property-tree-table tbody tr td:nth-child(1)")));
    }

    /** Opens a trait from the "(trait)" link on its group in the Properties card. */
    SchemaPage followTraitLink(String traitName) {
        card("Properties").findElement(By.cssSelector("button.property-tree-trait-link[aria-label='Open trait " + traitName + "']")).click();
        return awaitHeading(traitName);
    }

    static List<List<String>> propertyRows(WebElement container) {
        return container.findElements(By.xpath(".//section[contains(@class,'property-tree-group')]"
                        + " | .//table[contains(@class,'property-tree-table')]/tbody/tr"
                        + " | .//p[contains(@class,'property-tree-empty')]")).stream()
                .map(SchemaPage::propertyRow)
                .toList();
    }

    private static List<String> propertyRow(WebElement row) {
        return switch (row.getTagName()) {
            case "section" -> {
                List<String> path = texts(row.findElements(By.xpath(
                        "ancestor-or-self::section[contains(@class,'property-tree-group')]/header//span[contains(@class,'property-tree-group-name')]")));
                boolean trait = !row.findElements(By.cssSelector(":scope > header .property-tree-group-kind")).isEmpty();
                yield List.of("Group " + String.join(" > ", path) + (trait ? " (trait)" : ""));
            }
            case "p" -> List.of(text(row));
            default -> texts(row.findElements(By.tagName("td"))).subList(0, 3);
        };
    }

    SchemaPage toggleGroup(String name) {
        propertyTable().findElement(group(name)).findElement(By.cssSelector(":scope > header .property-tree-group-toggle")).click();
        return this;
    }

    /** Narrows the properties to those whose name or description contains the text. */
    SchemaPage filterProperties(String text) {
        WebElement input = propertyTable().findElement(By.cssSelector("input[name='property-filter']"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    SchemaPage clearPropertyFilter() {
        propertyTable().findElement(By.cssSelector("input[name='property-filter'] + .filter-clear")).click();
        return this;
    }

    /**
     * Each link in an item type's Links card, in reading order: the label over the arrow out, the
     * label under the arrow back, and the target's box.
     */
    List<List<String>> linkDiagrams() {
        return content().findElements(By.tagName("item-type-link-view")).stream()
                .map(card -> texts(card.findElements(By.cssSelector(".item-type-link-arrow-label, .item-type-link-box"))))
                .toList();
    }

    /** Unfolds a link, if it isn't already -- saved links start folded down to their arrows. */
    SchemaPage expandLink(String perspectiveName) {
        if (!linkExpanded(perspectiveName)) {
            linkCard(perspectiveName).findElement(By.cssSelector(".item-type-link-toggle")).click();
        }
        return this;
    }

    boolean linkExpanded(String perspectiveName) {
        return "true".equals(linkCard(perspectiveName).findElement(By.cssSelector(".item-type-link-toggle")).getDomAttribute("aria-expanded"));
    }

    /** The descriptions a link card's (i) icons show on hover, in reading order. */
    List<String> linkDescriptions(String perspectiveName) {
        return linkCard(perspectiveName).findElements(By.cssSelector(".item-type-link-description")).stream()
                .map(icon -> icon.getDomAttribute("title"))
                .toList();
    }

    /** The edit and delete buttons for a link itself, by accessible name. */
    List<String> linkActions(String perspectiveName) {
        return linkCard(perspectiveName).findElements(By.cssSelector(
                        ".item-type-link-actions button, .item-type-link-arrow-middle button")).stream()
                .map(button -> button.getDomAttribute("aria-label"))
                .toList();
    }

    /** Where an inherited link comes from, e.g. "(trait)". */
    String linkSource(String perspectiveName) {
        return text(linkCard(perspectiveName).findElement(By.cssSelector(".item-type-link-source")));
    }

    /** Follows an inherited link's "(trait)" or "(supertype)" link to where it comes from. */
    SchemaPage followLinkSource(String perspectiveName, String sourceName) {
        linkCard(perspectiveName).findElement(By.cssSelector(".item-type-link-source button")).click();
        return awaitHeading(sourceName);
    }

    /** Opens the link editor from a link's edit button. */
    SchemaPage editLink(String perspectiveName) {
        return openEditor(linkCard(perspectiveName).findElement(By.cssSelector("button[aria-label='Edit link " + perspectiveName + "']")));
    }

    /** Opens the link editor for a new link, from the Links card's title bar. */
    SchemaPage newLink() {
        return openEditor(card("Links").findElement(By.cssSelector("collapsible-card-actions button[aria-label='New link']")));
    }

    /**
     * The open link editor's fields, label to value: "Links to", then each direction's fields as
     * "heading: label", e.g. "Book to Author: Name".
     */
    Map<String, String> linkEditorFields() {
        return settled(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            WebElement target = dialog().findElement(By.cssSelector("link-edit-fields > :first-child"));
            fields.put(text(target.findElement(By.cssSelector(".input-control-name"))), fieldValue(target));
            for (WebElement section : dialog().findElements(By.cssSelector("section.link-edit-direction"))) {
                String heading = text(section.findElement(By.tagName("h3")));
                for (WebElement field : section.findElements(By.cssSelector("property-edit-fields > *"))) {
                    fields.put(heading + ": " + text(field.findElement(By.cssSelector(".input-control-name"))), fieldValue(field));
                }
            }
            return fields;
        });
    }

    /** Replaces a field's value in one direction of the link editor, by that direction's heading. */
    SchemaPage typeInLinkEditor(String direction, String fieldLabel, String text) {
        WebElement input = dialog().findElement(By.xpath(".//section[contains(@class,'link-edit-direction')][h3[.='" + direction + "']]"
                + "//input-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    /** The link card whose arrows carry this perspective name. */
    WebElement linkCard(String perspectiveName) {
        return content().findElement(By.xpath(".//item-type-link-view[.//span[contains(@class,'item-type-link-arrow-label')][starts-with(.,'"
                + perspectiveName + " (')]]"));
    }

    static void toggleLinkProperties(WebElement card) {
        card.findElement(By.cssSelector(".item-type-link-properties-toggle")).click();
    }

    /** A trait's Links tab: just the perspective names. */
    List<String> linkNames() {
        return texts(content().findElements(By.cssSelector("ul.link-list li")));
    }

    List<String> stateMachineNames() {
        return texts(content().findElements(By.cssSelector(".item-type-state-machine-name")));
    }

    /** The NORMAL states drawn in one machine's diagram (START/END are unlabeled dots). */
    List<String> stateNames(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-node-label")).stream().map(SchemaPage::text).toList();
    }

    int pseudoStateCount(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-node-pseudo-fill")).size();
    }

    /** The transition labels drawn in one machine's diagram. */
    List<String> transitionNames(String machineName) {
        return texts(machine(machineName).findElements(By.cssSelector(".sm-edge-label")));
    }

    int edgeCount(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-edge")).size();
    }

    private WebElement machine(String name) {
        return content().findElement(By.xpath(".//div[contains(@class,'item-type-state-machine')][h3[.='" + name + "']]"));
    }

    private <T> T settled(Supplier<T> read) {
        return await().until(d -> read.get());
    }

    private WebElement content() {
        return driver.findElement(CONTENT);
    }

    private WebElement card(String title) {
        return content().findElement(By.xpath(
                ".//collapsible-card[.//span[contains(@class,'collapsible-card-title')][.='" + title + "']]"));
    }

    // An item type's properties are in its Properties card -- the Links card holds link property
    // tables too -- while a trait's are on its own Properties tab.
    private WebElement propertyTable() {
        List<WebElement> cards = content().findElements(By.xpath(
                ".//collapsible-card[.//span[contains(@class,'collapsible-card-title')][.='Properties']]"));
        return cards.isEmpty() ? content() : cards.getFirst();
    }

    private WebElement footerButton(String label) {
        return footerButtons(label).getFirst();
    }

    private List<WebElement> footerButtons(String label) {
        return content().findElements(By.xpath(".//item-type-structure-footer//button[.='" + label + "']"));
    }

    static List<String> texts(List<WebElement> elements) {
        return elements.stream().map(SchemaPage::text).toList();
    }

    static String text(WebElement element) {
        return element.getDomProperty("textContent").trim();
    }

    // Tries again if React replaces an element mid-read, as it does just after a selection changes.
    private WebDriverWait await() {
        return (WebDriverWait) new WebDriverWait(driver, Duration.ofSeconds(15)).ignoring(StaleElementReferenceException.class);
    }
}
