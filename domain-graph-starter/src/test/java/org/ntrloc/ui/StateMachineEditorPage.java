package org.ntrloc.ui;

import static org.ntrloc.ui.SchemaPage.texts;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The state machine editor dialog: its name and description, the diagram (states, transitions and
 * the buttons on whichever is selected), the Details panel, and Add state -- then Close, or once
 * something has changed, Cancel and Save. Pointer
 * work -- selecting, dragging -- goes through real mouse actions, as the diagram reacts to pointer
 * events, not clicks.
 */
class StateMachineEditorPage {

    private final WebDriver driver;

    private StateMachineEditorPage(WebDriver driver) {
        this.driver = driver;
    }

    static StateMachineEditorPage of(WebDriver driver) {
        StateMachineEditorPage page = new StateMachineEditorPage(driver);
        page.await().until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("state-machine-edit-dialog .diagram-canvas svg")));
        return page;
    }

    StateMachineEditorPage typeMachineName(String name) {
        replaceText(dialog().findElement(By.xpath(".//state-machine-edit-fields" + field("Name"))), name);
        return this;
    }

    /** The NORMAL states drawn in the diagram. */
    List<String> stateNames() {
        return texts(dialog().findElements(By.cssSelector(".sm-state-label")));
    }

    List<String> transitionNames() {
        return texts(dialog().findElements(By.cssSelector(".diagram-edge-label")));
    }

    StateMachineEditorPage selectState(String name) {
        press(state(name));
        await().until(d -> !d.findElements(By.cssSelector(".sm-state-selected")).isEmpty());
        return this;
    }

    StateMachineEditorPage selectTransition(String name) {
        press(transitionLabel(name));
        await().until(d -> !d.findElements(By.cssSelector(".diagram-edge-selected")).isEmpty());
        return this;
    }

    /** Replaces a field of what's selected, in the Details panel. */
    StateMachineEditorPage typeInDetails(String fieldLabel, String text) {
        replaceText(dialog().findElement(By.xpath(".//diagram-inspector-details" + field(fieldLabel))), text);
        return this;
    }

    /** What's wrong with the selected thing's name, as Details shows it; empty if nothing is. */
    String detailsProblem() {
        return texts(dialog().findElements(By.cssSelector(".diagram-inspector-problem"))).stream().findFirst().orElse("");
    }

    StateMachineEditorPage addState() {
        button("Add state").click();
        return this;
    }

    /** Deletes what's selected, with the delete button the diagram shows on it. */
    StateMachineEditorPage deleteSelected() {
        dialog().findElement(By.cssSelector(".diagram-icon-delete")).click();
        return this;
    }

    /** Draws a new transition from the selected state's add button to another state. */
    StateMachineEditorPage drawTransitionTo(String stateName) {
        drag(dialog().findElement(By.cssSelector(".diagram-icon-add")), state(stateName));
        return this;
    }

    /** Drags the selected transition's start ('from') or end ('to') to another state. */
    StateMachineEditorPage moveSelectedTransitionEnd(String end, String stateName) {
        List<WebElement> ends = dialog().findElements(By.cssSelector(".diagram-edge-selected .diagram-edge-end"));
        drag(ends.get(end.equals("from") ? 0 : 1), state(stateName));
        return this;
    }

    /** Drags a state by an offset, in screen pixels. */
    StateMachineEditorPage moveState(String name, int dx, int dy) {
        new Actions(driver).moveToElement(state(name)).clickAndHold()
                .moveByOffset(dx / 2, dy / 2).moveByOffset(dx - dx / 2, dy - dy / 2).release().perform();
        return this;
    }

    StateMachineEditorPage undo() {
        new Actions(driver).keyDown(Keys.CONTROL).sendKeys("z").keyUp(Keys.CONTROL).perform();
        return this;
    }

    StateMachineEditorPage redo() {
        new Actions(driver).keyDown(Keys.CONTROL).keyDown(Keys.SHIFT).sendKeys("z").keyUp(Keys.SHIFT).keyUp(Keys.CONTROL).perform();
        return this;
    }

    boolean canSave() {
        return button("Save").isEnabled();
    }

    /** Saves, and waits for the editor to close. */
    void save() {
        button("Save").click();
        awaitClosed();
    }

    void cancel() {
        button("Cancel").click();
        awaitClosed();
    }

    void close() {
        button("Close").click();
        awaitClosed();
    }

    /** The buttons at the bottom right: Close, or Cancel and Save. */
    List<String> closingButtons() {
        return texts(dialog().findElements(By.xpath(".//dialog-actions//button[normalize-space(.)!='Add state']")));
    }

    /** Presses Escape -- which closes the editor only while there's nothing to lose. */
    StateMachineEditorPage pressEscape() {
        new Actions(driver).sendKeys(Keys.ESCAPE).perform();
        return this;
    }

    boolean isOpen() {
        return !driver.findElements(By.cssSelector("state-machine-edit-dialog")).isEmpty();
    }

    private void awaitClosed() {
        await().until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("state-machine-edit-dialog")));
    }

    private static String field(String label) {
        return "//input-control[span[contains(@class,'input-control-name')][.='" + label + "']]//input";
    }

    private void replaceText(WebElement input, String text) {
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
    }

    private WebElement state(String name) {
        return dialog().findElement(By.xpath(".//*[contains(@class,'sm-state-label')][.='" + name + "']/preceding-sibling::*[1]"));
    }

    private WebElement transitionLabel(String name) {
        return dialog().findElement(By.xpath(".//*[contains(@class,'diagram-edge-label')][.='" + name + "']"));
    }

    private WebElement button(String label) {
        return dialog().findElement(By.xpath(".//dialog-actions//button[normalize-space(.)='" + label + "']"));
    }

    private void press(WebElement element) {
        new Actions(driver).moveToElement(element).click().perform();
    }

    // Through a few points on the way, so the drag passes the distances at which a line end lets go
    // of one state and snaps to another.
    private void drag(WebElement from, WebElement to) {
        Actions actions = new Actions(driver).moveToElement(from).clickAndHold().moveByOffset(0, -60);
        actions.moveToElement(to).moveByOffset(2, 2).moveToElement(to).release().perform();
    }

    private WebElement dialog() {
        return driver.findElement(By.tagName("dialog-view"));
    }

    private WebDriverWait await() {
        return (WebDriverWait) new WebDriverWait(driver, Duration.ofSeconds(15)).ignoring(StaleElementReferenceException.class);
    }
}
