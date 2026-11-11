package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.draft.DraftService;
import org.ntrloc.graph.principal.InternalPrincipalResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The Access view, end to end: an admin edits a user (details, admin rights, password, tokens, groups),
 * a group (name, parent, members), and grants, live and in a draft. Every test makes its own users
 * and groups; THING_TYPE and its marker are shared and never changed.
 */
class AccessUiTest extends AbstractUiIntegrationTest {

    private static final String THING_TYPE = "UiAccessThing";
    private static UUID thingTypeId;
    private static UUID thingNameId;
    private static String thingMarker;
    private static UUID thingMarkerId;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader grants;

    @Autowired
    private DraftService draftService;

    @Autowired
    private InternalPrincipalResolver principals;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private AccessPage access;

    @BeforeEach
    void signInAndOpenAccess() {
        createFixtureOnce();
        openAdminUiAs(admin(), "/access");
        access = AccessPage.of(driver);
    }

    @AfterEach
    void discardOpenDrafts() {
        var adminPrincipal = principals.byId(admin().id()).orElseThrow();
        draftService.openDrafts(adminPrincipal).forEach(d -> draftService.discard(d.id(), adminPrincipal));
    }

    private void createFixtureOnce() {
        if (thingTypeId != null) return;
        var type = testData.createItemType(THING_TYPE, null, List.of(property("name", PropertyType.STRING)), List.of());
        thingTypeId = type.id();
        thingNameId = UiTestData.propertyId(type, "name");
        thingMarker = "UI access marker";
        thingMarkerId = accessService.createMarker(thingMarker, "", "ITEM_TYPE", thingTypeId).id();
    }

    private UserStore.UserRow newUser(String name) {
        return userStore.createUser(name + " " + tag, "ui-access-" + UUID.randomUUID() + "@example.com", false);
    }

    private UUID newGroup(String name) {
        return userStore.createUserGroup(name + " " + tag).id();
    }

    private void reload() {
        openAdminUiAs(admin(), "/access");
        access = AccessPage.of(driver);
    }

    @Test
    void editingAUser_needsAName_andRenamesThemInTheList() {
        var user = newUser("Rename me");
        reload();
        access.select(user.displayName()).click("Edit");

        access.type("Display name", "");
        assertThat(access.messages()).contains("A user needs a name.");

        access.type("Display name", "Renamed " + tag).click("Save");

        access.await().until(d -> access.listed().contains("Renamed " + tag));
        assertThat(userStore.findUserById(user.id()).orElseThrow().displayName()).isEqualTo("Renamed " + tag);
    }

    @Test
    void makingAUserAnAdmin_asksFirst() {
        var user = newUser("Promote me");
        reload();
        access.select(user.displayName());

        access.click("Edit").toggleSwitch("Admin").click("Save").confirm(false);
        assertThat(userStore.findUserById(user.id()).orElseThrow().isAdmin()).isFalse();

        // Cancelling the question leaves the form open, unsaved.
        access.click("Save").confirm(true);
        access.await().until(d -> access.headerBadges().contains("Admin"));
        assertThat(userStore.findUserById(user.id()).orElseThrow().isAdmin()).isTrue();
    }

    @Test
    void resettingAPassword_needsTwelveCharactersTypedTwice() {
        var user = newUser("Password");
        userStore.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
        String before = userStore.findCredentialsByUserId(user.id()).orElseThrow().passwordHash();
        reload();
        access.select(user.displayName()).click("Reset password");

        access.type("New password", "short");
        assertThat(access.dialogText()).contains("At least 12 characters.");
        access.type("New password", "a-long-enough-password").type("Repeat it", "something-else-entirely");
        assertThat(access.dialogText()).contains("The two passwords don't match.");
        assertThat(access.dialogButtonEnabled("Reset password")).isFalse();

        access.type("Repeat it", "a-long-enough-password").dialogButton("Reset password").awaitDialogClosed();

        assertThat(userStore.findCredentialsByUserId(user.id()).orElseThrow().passwordHash()).isNotEqualTo(before);
    }

    @Test
    void aTokenIsShownOnceWhenCreated_andCanBeRevoked() {
        var user = newUser("Tokens");
        reload();
        access.select(user.displayName()).click("New token");

        access.type("Name", "ci").dialogChoose("Expires after", "Never").dialogButton("Create token");
        access.await().until(d -> access.dialogText().contains("Token created"));
        assertThat(access.dialogText()).contains("this is the only time");
        access.dialogButton("Done").awaitDialogClosed();

        assertThat(access.rows()).containsExactly("ci");
        assertThat(userStore.listTokensForUser(user.id())).singleElement().satisfies(t -> assertThat(t.expiresAt()).isNull());

        access.rowAction("Revoke ci").confirm(true);
        access.await().until(d -> access.rows().isEmpty());
        assertThat(userStore.listTokensForUser(user.id())).isEmpty();
    }

    @Test
    void tickingAGroup_addsTheUserToIt_andUntickingRemovesThem() {
        var user = newUser("Member");
        UUID group = newGroup("Joinable");
        reload();
        access.select(user.displayName()).detailTab("Group membership");

        access.tick("Joinable " + tag);
        access.await().until(d -> userStore.getUserGroupIdsForUser(user.id()).contains(group));

        access.tick("Joinable " + tag);
        access.await().until(d -> !userStore.getUserGroupIdsForUser(user.id()).contains(group));
    }

    @Test
    void aGroupCanBeRenamedAndMoved_andGainAndLoseMembers() {
        UUID group = newGroup("Movable");
        UUID parent = newGroup("Parent");
        var user = newUser("Joiner");
        reload();
        access.section("Groups").select("Movable " + tag);

        access.click("Edit").type("Name", "Moved " + tag).chooseParent("Parent " + tag).click("Save");
        access.await().until(d -> access.heading().equals("Moved " + tag));
        assertThat(userStore.findUserGroupById(group).orElseThrow().name()).isEqualTo("Moved " + tag);
        assertThat(userStore.listContainingUserGroups(group)).extracting(UserStore.UserGroupRow::id).containsExactly(parent);

        access.choose("Add member", user.displayName());
        access.await().until(d -> access.rows().contains(user.displayName()));
        access.rowAction("Remove " + user.displayName());
        access.await().until(d -> !access.rows().contains(user.displayName()));
        assertThat(userStore.getUserGroupIdsForUser(user.id())).doesNotContain(group);
    }

    @Test
    void grantingAGroup_isSeenOnItsMembers_asThroughTheGroup() {
        UUID group = newGroup("Granted");
        var user = newUser("Inherits");
        userStore.addUserToUserGroup(user.id(), group);
        reload();
        access.section("Groups").select("Granted " + tag).detailTab("Permission grants");

        access.toggleTypePill(THING_TYPE, "Create");
        access.toggleMarkerPill(THING_TYPE, thingMarker, "Items", null, "Read");
        access.toggleMarkerPill(THING_TYPE, thingMarker, "Properties", "name", "Write");

        assertThat(access.typePill(THING_TYPE, "Create")).isEqualTo("direct");
        assertThat(access.markerSummary(THING_TYPE, thingMarker)).isEqualTo("Read · 1 property");
        assertThat(grants.getPermissions("USER_GROUP", group)).containsExactlyInAnyOrder(
                Permission.itemType(thingTypeId, "create"),
                Permission.forMarker(thingMarkerId, Permission.AREA_ITEM, null, "read"),
                Permission.forMarker(thingMarkerId, Permission.AREA_PROPERTY, thingNameId, "write"));

        access.section("Users").select(user.displayName()).detailTab("Permission grants");
        assertThat(access.typePill(THING_TYPE, "Create")).isEqualTo("inherited");
        assertThat(access.cardSummary(THING_TYPE)).contains("Create");

        access.toggleTypePill(THING_TYPE, "Read");
        assertThat(grants.getPermissions("USER", user.id())).containsExactly(Permission.itemType(thingTypeId, "read"));
    }

    @Test
    void inADraft_whoUsersAndGroupsAreIsReadOnly_butGrantsAreDrafted() {
        var user = newUser("Drafted");
        UUID group = newGroup("Drafted group");
        reload();
        DraftSwitchPage.of(driver).startDraft("Access draft " + tag);

        access.select(user.displayName());
        assertThat(access.draftNote()).contains("read-only here");
        assertThat(access.hasButton("Edit")).isFalse();
        assertThat(access.hasButton("New token")).isFalse();
        access.detailTab("Group membership");
        assertThat(access.draftNote()).contains("isn't drafted yet");
        assertThat(access.tickable("Drafted group " + tag)).isFalse();

        access.section("Groups").select("Drafted group " + tag);
        assertThat(access.draftNote()).contains("aren't drafted yet");
        access.detailTab("Permission grants").toggleTypePill(THING_TYPE, "Create");

        assertThat(grants.getPermissions("USER_GROUP", group)).isEmpty();
    }
}
