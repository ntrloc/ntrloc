package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.draft.DraftService;
import org.ntrloc.graph.principal.InternalPrincipalResolver;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Groups edited in a draft, and what live puts in question, shown beside what it's about
 * (docs/ntrloc-system-model-notes.md §13.4.1, plan step 2.6). Live's changes are made directly, as
 * another admin would. Every test discards whatever drafts it leaves open.
 */
class DraftGroupsUiTest extends AbstractUiIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private DraftService draftService;

    @Autowired
    private InternalPrincipalResolver principals;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private DraftSwitchPage drafts;
    private AccessPage access;
    private UUID root;

    @BeforeEach
    void findTheRoot() {
        root = userGroupStore.findUserGroupByName("everyone").orElseThrow().id();
    }

    @AfterEach
    void discardOpenDrafts() {
        var adminPrincipal = principals.byId(admin().id()).orElseThrow();
        draftService.openDrafts(adminPrincipal).forEach(d -> draftService.discard(d.id(), adminPrincipal));
    }

    // Opens Access once the groups the test needs exist, and starts a draft.
    private void openDraft(String name) {
        openAdminUiAs(admin(), "/access");
        drafts = DraftSwitchPage.of(driver);
        drafts.startDraft(name + " " + tag);
        access = AccessPage.of(driver).section("Groups");
    }

    private UUID group(String name, UUID parent) {
        return userService.createUserGroup(name + " " + tag, parent).id();
    }

    private String named(String name) {
        return name + " " + tag;
    }

    private UUID parentOf(UUID group) {
        return userGroupStore.findUserGroupById(group).orElseThrow().parentId();
    }

    // The group's parent in live, by name (a failure then says where it is).
    private String parentNameOf(UUID group) {
        return userGroupStore.findUserGroupById(parentOf(group)).orElseThrow().name();
    }

    // --- The P1/P2/P3 example: A under P1; the draft moves it to P2; live then moves it to P3 ---

    private record Moves(UUID a, UUID p1, UUID p2, UUID p3) {}

    private Moves bothMoveA() {
        UUID p1 = group("P1", root);
        UUID p2 = group("P2", root);
        UUID p3 = group("P3", root);
        UUID a = group("A", p1);
        openDraft("Reorg");
        access.select(named("A")).click("Edit").chooseParent(named("P2")).click("Save");
        access.await().until(d -> access.modified().contains(named("A")));

        userService.setParent(a, p3);

        access.await().until(d -> !access.reconcileItems().isEmpty());
        return new Moves(a, p1, p2, p3);
    }

    @Test
    void bothSidesMovingAGroup_showsTheConflictBesideItsParent_withBothSides() {
        bothMoveA();

        assertThat(access.reconcileItems()).singleElement().asString()
                .contains("Moved in both: draft under " + named("P2") + " · live under " + named("P3") + " (" + named("P1") + " when the draft started).")
                .contains(named("A") + " was moved from " + named("P1") + " to " + named("P3"))
                .contains("Keep draft change").contains("Preserve live change");
        assertThat(access.needingReconciling()).contains("Access", "Groups", named("A"), "Info / Members");
        assertThat(drafts.noticeBar()).contains("1 change needs reconciling");

        drafts.openApply();
        assertThat(drafts.dialogButtonEnabled("Apply")).isFalse();
        drafts.cancel();
    }

    @Test
    void keepingTheDraftsMove_appliesIt_fromWhereLiveHasTheGroup() {
        var m = bothMoveA();

        access.settle("Keep draft change");

        access.await().until(d -> access.reconcileItems().isEmpty());
        assertThat(access.needingReconciling()).isEmpty();
        assertThat(drafts.apply()).containsExactly("1 group change made in live");
        drafts.done();
        assertThat(parentOf(m.a())).isEqualTo(m.p2());
    }

    @Test
    void preservingLivesMove_dropsTheDrafts() {
        var m = bothMoveA();

        access.settle("Preserve live change");

        access.await().until(d -> access.reconcileItems().isEmpty());
        assertThat(access.modified()).doesNotContain(named("A"));
        drafts.apply();
        drafts.done();
        assertThat(parentOf(m.a())).isEqualTo(m.p3());
    }

    @Test
    void choosingAnotherParent_settlesTheConflictToo() {
        var m = bothMoveA();
        UUID p4 = group("P4", root);
        access.click("Edit").chooseParent(named("P4")).click("Save");

        access.await().until(d -> !access.hasButton("Save") && access.parentGroup().equals(named("P4")));
        access.await().until(d -> access.reconcileItems().isEmpty());
        drafts.apply();
        drafts.done();
        assertThat(parentNameOf(m.a())).isEqualTo(named("P4"));
    }

    // --- A group the draft deletes ---

    @Test
    void aGroupDeletedInTheDraft_staysSelected_struckThrough_andCanBeRestored() {
        UUID doomed = group("Doomed", root);
        openDraft("Delete");

        access.select(named("Doomed")).click("Delete").dialogButton("Delete").awaitDialogClosed();

        access.await().until(d -> access.struckThrough(named("Doomed")));
        assertThat(access.heading()).isEqualTo(named("Doomed"));
        assertThat(access.draftNote()).contains("Deleted in this draft");
        assertThat(userGroupStore.findUserGroupById(doomed)).isPresent();

        access.click("Restore");
        access.await().until(d -> !access.struckThrough(named("Doomed")));
        assertThat(access.hasButton("Delete")).isTrue();
    }

    @Test
    void liveAddingASubgroupUnderAGroupTheDraftDeletes_isSettledOnThatGroup() {
        UUID doomed = group("Doomed", root);
        openDraft("Delete");
        access.select(named("Doomed")).click("Delete").dialogButton("Delete").awaitDialogClosed();
        access.await().until(d -> access.struckThrough(named("Doomed")));

        UUID added = group("Added in live", doomed);

        access.await().until(d -> !access.reconcileItems().isEmpty());
        assertThat(access.reconcileItems()).singleElement().asString()
                .contains("The draft deletes this group, but live has added a subgroup under it: " + named("Added in live") + ".");
        assertThat(access.listed()).contains(named("Added in live"));

        access.settle("Delete its new subgroups too");

        access.await().until(d -> access.reconcileItems().isEmpty());
        assertThat(access.struckThrough(named("Added in live"))).isTrue();
        drafts.apply();
        drafts.done();
        assertThat(userGroupStore.findUserGroupById(doomed)).isEmpty();
        assertThat(userGroupStore.findUserGroupById(added)).isEmpty();
    }
}
