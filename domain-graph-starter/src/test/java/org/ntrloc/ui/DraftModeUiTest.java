package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.draft.DraftParticipantRecord;
import org.ntrloc.graph.draft.DraftService;
import org.ntrloc.graph.principal.InternalPrincipalResolver;
import org.openqa.selenium.By;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Draft mode, end to end, as an admin: start a draft from the header's switch, change a grant in it,
 * apply or discard it, invite participants, and search while in it. Every test discards whatever
 * drafts it leaves open, since only one may be open at a time.
 */
class DraftModeUiTest extends AbstractUiIntegrationTest {

    private static final String WIDGET_TYPE = "UiDraftWidget";
    private static UUID widgetTypeId;

    @Autowired
    private UserStore userStore;

    @Autowired
    private org.ntrloc.graph.db.partition.security.UserService userService;

    @Autowired
    private GrantStoreReader grants;

    @Autowired
    private DraftService draftService;

    @Autowired
    private InternalPrincipalResolver principals;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private DraftSwitchPage drafts;

    @BeforeEach
    void signInAndOpenAccess() {
        createFixtureOnce();
        openAdminUiAs(admin(), "/access");
        drafts = DraftSwitchPage.of(driver);
    }

    @AfterEach
    void discardOpenDrafts() {
        var adminPrincipal = principals.byId(admin().id()).orElseThrow();
        draftService.openDrafts(adminPrincipal).forEach(d -> draftService.discard(d.id(), adminPrincipal));
    }

    private void createFixtureOnce() {
        if (widgetTypeId != null) return;
        var type = testData.createItemType(WIDGET_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of());
        widgetTypeId = type.id();
        testData.createItem(widgetTypeId, Map.of(UiTestData.propertyId(type, "name"), "Draft widget"));
    }

    // Groups made after the page loaded its lists only show after a reload.
    private void reload() {
        openAdminUiAs(admin(), "/access");
        drafts = DraftSwitchPage.of(driver);
    }

    // Grants the group Create on WIDGET_TYPE through its Permission grants tab, in whatever mode the tab is in.
    private void grantCreate(String groupName) {
        AccessPage.of(driver).section("Groups").select(groupName).detailTab("Permission grants").toggleTypePill(WIDGET_TYPE, "Create");
    }

    @Test
    void startingADraft_switchesTheTabIntoIt_andChoosingLiveSwitchesBack() {
        assertThat(drafts.current()).isEqualTo("Live");
        assertThat(drafts.menuActions()).containsExactly("New draft");

        drafts.startDraft("Switch " + tag);

        assertThat(drafts.headerMarkedAsDraft()).isTrue();
        assertThat(drafts.choices()).containsExactly("Live", "Switch " + tag);
        assertThat(drafts.menuActions()).containsExactly("Participants…", "Apply draft…", "Discard draft…", "New draft");

        drafts.choose("Live");
        assertThat(drafts.current()).isEqualTo("Live");
        assertThat(drafts.headerMarkedAsDraft()).isFalse();

        drafts.choose("Switch " + tag);
        assertThat(drafts.current()).isEqualTo("DRAFT Switch " + tag);
    }

    @Test
    void applyingADraft_putsItsGrantsInLive_andSaysWhatChanged() {
        UUID group = userStore.createUserGroup("Apply " + tag).id();
        reload();
        drafts.startDraft("Apply " + tag);
        grantCreate("Apply " + tag);
        assertThat(grants.getPermissions("USER_GROUP", group)).isEmpty();

        assertThat(drafts.apply()).containsExactly("1 permission changed in live");
        assertThat(drafts.dialogText()).contains("is applied and closed");
        drafts.done();

        assertThat(drafts.current()).isEqualTo("Live");
        assertThat(grants.getPermissions("USER_GROUP", group)).containsExactly(Permission.itemType(widgetTypeId, "create"));
    }

    @Test
    void applyingADraftThatChangedNothing_saysSo() {
        drafts.startDraft("Empty " + tag);

        assertThat(drafts.apply()).isEmpty();
        assertThat(drafts.dialogText()).contains("didn't change any permissions");
        drafts.done();
    }

    @Test
    void discardingADraft_leavesLiveAsItWas() {
        UUID group = userStore.createUserGroup("Discard " + tag).id();
        reload();
        drafts.startDraft("Discard " + tag);
        grantCreate("Discard " + tag);

        drafts.discard();

        assertThat(drafts.current()).isEqualTo("Live");
        assertThat(grants.getPermissions("USER_GROUP", group)).isEmpty();
        assertThat(drafts.choices()).containsExactly("Live");
    }

    @Test
    void dotsLeadFromTheSidebarToEachChangedGrant_andGoWhenTheChangeIsUndone() {
        userStore.createUserGroup("Dotted " + tag);
        reload();
        drafts.startDraft("Dots " + tag);
        AccessPage access = AccessPage.of(driver);
        assertThat(access.modified()).isEmpty();

        grantCreate("Dotted " + tag);
        access.await().until(d -> !access.modified().isEmpty());
        assertThat(access.modified()).containsExactlyInAnyOrder(
                "Access", "Groups", "Dotted " + tag, "Permission grants", WIDGET_TYPE, "pill Create");

        grantCreate("Dotted " + tag);
        access.await().until(d -> access.modified().isEmpty());
    }

    // The example behind reconciliation (notes §13.3): another admin moves the group in live.
    @Test
    void aGroupMovedInLive_marksTheDraftsChange_andApplyWaitsUntilItsReconciled() {
        UUID oldParent = userStore.createUserGroup("Old parent " + tag).id();
        UUID newParent = userStore.createUserGroup("New parent " + tag).id();
        UUID moved = userStore.createUserGroup("Moved " + tag, oldParent).id();
        reload();
        drafts.startDraft("Reconcile " + tag);
        grantCreate("Moved " + tag);

        userStore.setParent(moved, newParent);

        drafts.openReview();
        assertThat(drafts.dialogText()).contains("Moved " + tag + ": " + WIDGET_TYPE + ": Create granted")
                .contains("This group has changed in live");
        drafts.done();
        AccessPage access = AccessPage.of(driver);
        access.await().until(d -> access.needingReconciling().contains("pill Create"));
        assertThat(access.needingReconciling()).containsExactlyInAnyOrder(
                "DRAFT Reconcile " + tag, "1 change needs reconciling with live before this draft can be applied",
                "Access", "Groups", "Moved " + tag, "Permission grants", WIDGET_TYPE, "pill Create");

        drafts.openApply();
        assertThat(drafts.dialogText()).contains("1 change needs reconciling with live");
        assertThat(drafts.dialogButtonEnabled("Apply")).isFalse();
        drafts.cancel();

        drafts.openReview().resolveFirst("Keep the draft's change");
        access.await().until(d -> drafts.dialogText().contains("Nothing needs reconciling."));
        drafts.done();
        assertThat(access.needingReconciling()).isEmpty();
        assertThat(access.modified()).contains("pill Create");

        assertThat(drafts.apply()).containsExactly("1 permission changed in live");
        drafts.done();
    }

    // The other example behind notices (notes §13.6): nothing changed in the draft, and a group moves in live.
    @Test
    void aGroupMovedInLive_isAChangeToReview_inContextAndFromTheBar_untilAcknowledged() {
        UUID moving = userStore.createUserGroup("Moving " + tag).id();
        UUID target = userStore.createUserGroup("Target " + tag).id();
        reload();
        drafts.startDraft("Notices " + tag);
        AccessPage access = AccessPage.of(driver).section("Groups").select("Moving " + tag);

        userService.setParent(moving, target);

        access.await().until(d -> drafts.noticeBar().contains("1 live change to review"));
        assertThat(access.changedInLive()).contains("Access", "Groups", "Moving " + tag, "Info / Members");
        assertThat(access.liveNotices()).contains("Moving " + tag + " was moved from everyone to Target " + tag)
                .contains("system");

        access.acknowledgeFirstNotice();

        access.await().until(d -> drafts.noticeBar().isEmpty());
        assertThat(access.changedInLive()).isEmpty();
        assertThat(access.liveNotices()).isEmpty();
    }

    @Test
    void anAdminInvitesAndRemovesParticipants() {
        var user = userStore.createUser("Participant " + tag, "ui-draft-" + UUID.randomUUID() + "@example.com", false);
        drafts.startDraft("Participants " + tag).openParticipants();
        assertThat(drafts.dialogText()).contains("No one is invited.");

        drafts.invite(user.displayName());
        assertThat(drafts.participants()).containsExactly(user.displayName());
        UUID draftId = draftService.openDrafts(principals.byId(admin().id()).orElseThrow()).getFirst().id();
        assertThat(draftService.participants(draftId, principals.byId(admin().id()).orElseThrow()))
                .extracting(DraftParticipantRecord::userId).containsExactly(user.id());

        drafts.remove(user.displayName());
        assertThat(drafts.dialogText()).contains("No one is invited.");
        drafts.done();
    }

    @Test
    void inADraft_searchReadsItems_butOffersNoWayToChangeThem() {
        drafts.startDraft("Search " + tag);
        openAdminUiAs(admin(), "/search");
        // The draft mode is held in memory, so a page load returns the tab to live: switch back in.
        DraftSwitchPage.of(driver).choose("Search " + tag);

        SearchPanePage pane = SearchPanePage.openNew(driver).selectItemType(WIDGET_TYPE).search();
        assertThat(pane.resultLabels()).containsExactly("Draft widget");
        assertThat(driver.findElements(By.cssSelector(".search-result-item-delete-btn"))).isEmpty();

        driver.findElement(By.cssSelector(".search-result-item-open-btn")).click();
        new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(15))
                .until(d -> !d.findElements(By.cssSelector("item-pane-view item-pane-actions")).isEmpty());
        assertThat(driver.findElements(By.cssSelector("item-pane-actions button"))).isEmpty();

        DraftSwitchPage.of(driver).choose("Live");
        new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(15))
                .until(d -> !d.findElements(By.cssSelector("item-pane-actions button")).isEmpty());
    }
}
