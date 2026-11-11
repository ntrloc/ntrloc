package org.ntrloc.ui;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The draft switch at the right of the header, and the dialogs it opens: start a draft, switch between
 * live and a draft, apply or discard the current one, and manage its participants.
 */
class DraftSwitchPage {

    private static final By TRIGGER = By.cssSelector("draft-mode-switch .popover-button-trigger");
    private static final By DIALOG = By.cssSelector("dialog-view[role='dialog']");

    private final WebDriver driver;

    private DraftSwitchPage(WebDriver driver) {
        this.driver = driver;
    }

    static DraftSwitchPage of(WebDriver driver) {
        var page = new DraftSwitchPage(driver);
        page.await().until(ExpectedConditions.elementToBeClickable(TRIGGER));
        return page;
    }

    /** What the switch shows: "Live", or "DRAFT {name}". */
    String current() {
        return driver.findElement(TRIGGER).getText().replaceAll("\\s+", " ").trim();
    }

    boolean headerMarkedAsDraft() {
        return driver.findElement(By.cssSelector("layout-navbar")).getAttribute("data-draft") != null;
    }

    private WebElement openMenu() {
        WebElement trigger = driver.findElement(TRIGGER);
        if (!"true".equals(trigger.getAttribute("aria-expanded"))) trigger.click();
        return await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("draft-mode-options")));
    }

    private void menuAction(String label) {
        openMenu().findElement(By.xpath(".//button[normalize-space()='" + label + "']")).click();
    }

    /** The choices in the switch's list: "Live" and each draft's name. */
    List<String> choices() {
        List<String> names = openMenu().findElements(By.cssSelector("ul[role='listbox'] button")).stream().map(WebElement::getText).toList();
        driver.findElement(TRIGGER).click();
        return names;
    }

    List<String> menuActions() {
        List<String> labels = openMenu().findElements(By.cssSelector("draft-mode-actions button")).stream().map(WebElement::getText).toList();
        driver.findElement(TRIGGER).click();
        return labels;
    }

    DraftSwitchPage choose(String name) {
        openMenu().findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='" + name + "']")).click();
        await().until(d -> current().endsWith(name));
        return this;
    }

    DraftSwitchPage startDraft(String name) {
        menuAction("New draft");
        WebElement dialog = dialog();
        dialog.findElement(By.cssSelector("input")).sendKeys(name);
        dialog.findElement(By.xpath(".//button[normalize-space()='Create']")).click();
        await().until(d -> current().equals("DRAFT " + name));
        return this;
    }

    /** Applies the current draft and returns the summary's lines; the dialog is left open on it. */
    List<String> apply() {
        menuAction("Apply draft…");
        dialog().findElement(By.xpath(".//button[normalize-space()='Apply']")).click();
        WebElement summary = await().until(d -> d.findElements(By.xpath("//dialog-view[.//h3[normalize-space()='Draft applied']]")).stream().findFirst().orElse(null));
        return summary.findElements(By.cssSelector(".close-draft-outcomes li")).stream().map(WebElement::getText).toList();
    }

    String dialogText() {
        return dialog().findElement(By.cssSelector("dialog-body")).getText();
    }

    void done() {
        dialog().findElement(By.xpath(".//button[normalize-space()='Done']")).click();
        await().until(ExpectedConditions.invisibilityOfElementLocated(DIALOG));
    }

    DraftSwitchPage discard() {
        menuAction("Discard draft…");
        dialog().findElement(By.xpath(".//button[normalize-space()='Discard']")).click();
        await().until(ExpectedConditions.invisibilityOfElementLocated(DIALOG));
        return this;
    }

    /** Opens the current draft's participants and returns the dialog, for the methods below. */
    DraftSwitchPage openParticipants() {
        menuAction("Participants…");
        await().until(d -> !dialog().getText().contains("Loading…"));
        return this;
    }

    List<String> participants() {
        return dialog().findElements(By.cssSelector(".participants-rows .participants-row-main > span:first-child"))
                .stream().map(WebElement::getText).toList();
    }

    DraftSwitchPage invite(String displayName) {
        int before = participants().size();
        WebElement dialog = dialog();
        dialog.findElement(By.xpath(".//button[contains(@class,'popover-button-trigger') and normalize-space()='Invite']")).click();
        dialog.findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='" + displayName + "']")).click();
        await().until(d -> participants().size() == before + 1);
        return this;
    }

    DraftSwitchPage remove(String displayName) {
        int before = participants().size();
        dialog().findElement(By.cssSelector("button[aria-label='Remove " + displayName + "']")).click();
        await().until(d -> participants().size() == before - 1);
        return this;
    }

    private WebElement dialog() {
        return await().until(ExpectedConditions.visibilityOfElementLocated(DIALOG));
    }

    private WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }
}
