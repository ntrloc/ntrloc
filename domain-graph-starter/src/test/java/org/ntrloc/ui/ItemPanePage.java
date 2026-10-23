package org.ntrloc.ui;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * One item pane in the admin UI's Search view: its tabs, its read-only and edit-mode property
 * groups, and its Edit/Save/Cancel/Delete actions. Holds on to the pane element itself, so it keeps
 * working after an edit changes the pane's title.
 *
 * Text is read with textContent rather than getText(): the pane styles some text (e.g. its title)
 * with CSS case transforms, and getText() returns the rendered form.
 */
class ItemPanePage {

    private static final By TITLE = By.cssSelector(".pane-view-title");
    private static final By EDIT = By.cssSelector("item-pane-actions button[title='Edit']");
    private static final By SAVE = By.cssSelector("item-pane-actions button[title='Save']");
    private static final By SAVE_ERRORS = By.cssSelector(".item-pane-save-errors > div");

    private final WebDriver driver;
    private final WebElement pane;

    private ItemPanePage(WebDriver driver, WebElement pane) {
        this.driver = driver;
        this.pane = pane;
    }

    /** Waits for an item pane with this title to appear, and returns it. */
    static ItemPanePage titled(WebDriver driver, String title) {
        By pane = By.xpath("//pane-view[.//span[contains(@class,'pane-view-title')][.='" + title + "']]");
        return new ItemPanePage(driver, new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(ExpectedConditions.presenceOfElementLocated(pane)));
    }

    /** Waits until the item has loaded (or failed to), so its content can be read. */
    ItemPanePage awaitLoaded() {
        await().until(d -> !text(pane.findElement(By.cssSelector("item-pane-view"))).equals("Loading…"));
        return this;
    }

    String title() {
        return text(pane.findElement(TITLE));
    }

    /** The pane's whole content text, e.g. "Item not found." for a pane with no item to show. */
    String contentText() {
        return text(pane.findElement(By.cssSelector("item-pane-view")));
    }

    boolean isOpen() {
        try {
            pane.isDisplayed();
            return true;
        } catch (StaleElementReferenceException e) {
            return false;
        }
    }

    void awaitClosed() {
        await().until(ExpectedConditions.stalenessOf(pane));
    }

    List<String> tabs() {
        return pane.findElements(By.cssSelector("tab-view-tabs button[role='tab']")).stream().map(ItemPanePage::text).toList();
    }

    ItemPanePage openTab(String label) {
        WebElement tab = pane.findElement(By.xpath(".//tab-view-tabs/button[@role='tab'][.='" + label + "']"));
        tab.click();
        await().until(d -> "true".equals(tab.getDomAttribute("aria-selected")));
        return this;
    }

    /** The active tab's panel text, e.g. "No links." */
    String tabText() {
        return text(pane.findElement(By.cssSelector("tab-view-panel")));
    }

    /** The group headers shown on the Properties tab, in order (the root group has none). */
    List<String> groupPaths() {
        return pane.findElements(By.cssSelector(".item-pane-property-group-path")).stream().map(ItemPanePage::text).toList();
    }

    /** groupPath is null for the root group. */
    List<String> propertyNames(String groupPath) {
        return group(groupPath).findElements(By.cssSelector("input-control > .input-control-name")).stream().map(ItemPanePage::text).toList();
    }

    /** A property's displayed value on the read-only Properties tab. groupPath is null for the root group. */
    String value(String groupPath, String name) {
        return text(leaf(groupPath, name).findElement(By.cssSelector(".input-control-value")));
    }

    boolean canEdit() {
        return !pane.findElements(EDIT).isEmpty();
    }

    boolean isEditing() {
        return !pane.findElements(SAVE).isEmpty();
    }

    ItemPanePage edit() {
        pane.findElement(EDIT).click();
        await().until(d -> isEditing());
        return this;
    }

    /** Replaces the text in a text or number field. */
    ItemPanePage type(String groupPath, String name, String value) {
        WebElement field = leaf(groupPath, name).findElement(By.cssSelector("input.input-control-field"));
        field.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        if (!value.isEmpty()) {
            field.sendKeys(value);
        }
        return this;
    }

    ItemPanePage toggle(String groupPath, String name) {
        leaf(groupPath, name).findElement(By.cssSelector("input[type='checkbox']")).click();
        return this;
    }

    /** Picks one value from a single-valued controlled-list property's picker, by its label. */
    ItemPanePage pick(String name, String label) {
        WebElement leaf = leaf(null, name);
        leaf.findElement(By.cssSelector(".popover-button-trigger")).click();
        leaf.findElement(By.xpath(".//ul[@role='listbox']//button[.='" + label + "']")).click();
        return this;
    }

    /** Checks or unchecks values in a multi-valued controlled-list property's picker, by their labels. */
    ItemPanePage toggleChoices(String name, String... labels) {
        WebElement leaf = leaf(null, name);
        WebElement trigger = leaf.findElement(By.cssSelector(".popover-button-trigger"));
        await().until(d -> trigger.isEnabled());
        trigger.click();
        for (String label : labels) {
            leaf.findElement(By.xpath(".//ul[@role='listbox']//label[normalize-space()='" + label + "']/input")).click();
        }
        trigger.click();
        return this;
    }

    /** Saves, and waits for the pane to leave edit mode and show the reloaded item. */
    ItemPanePage save() {
        pane.findElement(SAVE).click();
        await().until(d -> !isEditing() && canEdit());
        return this;
    }

    /** Saves a change the server rejects, and returns the error messages the pane shows. */
    List<String> saveExpectingErrors() {
        pane.findElement(SAVE).click();
        await().until(d -> !pane.findElements(SAVE_ERRORS).isEmpty());
        return pane.findElements(SAVE_ERRORS).stream().map(ItemPanePage::text).toList();
    }

    ItemPanePage cancel() {
        pane.findElement(By.cssSelector("item-pane-actions button[title='Cancel']")).click();
        await().until(d -> !isEditing());
        return this;
    }

    /** Clicks Delete, then OK (confirm) or Cancel in the confirmation popup. */
    void delete(boolean confirm) {
        pane.findElement(By.cssSelector("item-pane-actions button[title='Delete']")).click();
        WebElement dialog = await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("modal-popup-view")));
        dialog.findElement(By.xpath(".//button[normalize-space()='" + (confirm ? "OK" : "Cancel") + "']")).click();
        await().until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("modal-popup-view")));
    }

    private WebElement group(String groupPath) {
        String isGroup = "contains(concat(' ', normalize-space(@class), ' '), ' item-pane-property-group ')";
        String pathHeader = "div[contains(concat(' ', normalize-space(@class), ' '), ' item-pane-property-group-path ')]";
        return pane.findElement(By.xpath(groupPath == null
                ? ".//div[" + isGroup + "][not(" + pathHeader + ")]"
                : ".//div[" + isGroup + "][" + pathHeader + "[.='" + groupPath + "']]"));
    }

    private WebElement leaf(String groupPath, String name) {
        return group(groupPath).findElement(By.xpath(".//input-control[span[@class='input-control-name'][.='" + name + "']]"));
    }

    private static String text(WebElement element) {
        return element.getDomProperty("textContent").trim();
    }

    private WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }
}
