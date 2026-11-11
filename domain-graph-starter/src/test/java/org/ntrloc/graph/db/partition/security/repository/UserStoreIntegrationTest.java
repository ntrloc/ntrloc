package org.ntrloc.graph.db.partition.security.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers UserStore's admin-facing listing/update/delete methods -- the ones
// AuthorizationTestDataInitializer/AccessAdminControllerIntegrationTest's fixture setup never
// exercises, since that only ever creates users/groups and reads them back, never lists, updates,
// or deletes. security_user/security_user_group are shared tables, so every test here gives its own
// rows a UUID-suffixed name/email and filters on that, never a shared literal.
class UserStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserStore userStore;

    // --- Users ---

    @Test
    void listUsers_includesCreatedUsers() {
        var user = userStore.createUser("Display Name", "user-" + UUID.randomUUID() + "@example.com", false);

        assertThat(userStore.listUsers()).contains(user);
    }

    @Test
    void findUserById_returnsTheMatchingUser() {
        var user = userStore.createUser("Display Name", "user-" + UUID.randomUUID() + "@example.com", false);

        var found = userStore.findUserById(user.id()).orElseThrow();
        assertThat(found).isEqualTo(user);
    }

    @Test
    void findUserById_returnsEmptyForAnUnknownId() {
        assertThat(userStore.findUserById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findUserByEmail_ignoresCase() {
        String email = "Mixed.Case-" + UUID.randomUUID() + "@Example.com";
        var user = userStore.createUser("Display Name", email, false);

        assertThat(userStore.findUserByEmail(email.toLowerCase())).contains(user);
        assertThat(userStore.findUserByEmail(email.toUpperCase())).contains(user);
    }

    @Test
    void findUserByEmail_returnsEmptyForAnUnknownEmail() {
        assertThat(userStore.findUserByEmail("nobody-" + UUID.randomUUID() + "@example.com")).isEmpty();
    }

    @Test
    void createUser_withAnEmailAlreadyInUseInAnotherCase_isRejected() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        userStore.createUser("First", email, false);

        assertThatThrownBy(() -> userStore.createUser("Second", email.toUpperCase(), false))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void updateUser_persistsChanges() {
        var user = userStore.createUser("Original Name", "original-" + UUID.randomUUID() + "@example.com", false);
        String newEmail = "updated-" + UUID.randomUUID() + "@example.com";

        userStore.updateUser(user.id(), "Updated Name", newEmail, true);

        var reloaded = userStore.findUserById(user.id()).orElseThrow();
        assertThat(reloaded.displayName()).isEqualTo("Updated Name");
        assertThat(reloaded.email()).isEqualTo(newEmail);
        assertThat(reloaded.isAdmin()).isTrue();
    }

    // --- UserGroups ---

    @Test
    void listUserGroups_includesCreatedUserGroups() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());

        assertThat(userStore.listUserGroups()).contains(group);
    }

    @Test
    void updateUserGroup_persistsNewName() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());

        userStore.updateUserGroup(group.id(), "renamed-" + UUID.randomUUID());

        assertThat(userStore.findUserGroupById(group.id())).isPresent().get()
                .satisfies(g -> assertThat(g.name()).startsWith("renamed-"));
    }

    @Test
    void deleteUserGroup_removesIt() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());

        assertThat(userStore.deleteUserGroup(group.id(), false)).containsExactly(group.id());

        assertThat(userStore.findUserGroupById(group.id())).isEmpty();
    }

    @Test
    void deleteUserGroup_withSubgroups_isRefused_unlessForced_whichDeletesThemToo() {
        var top = userStore.createUserGroup("top-" + UUID.randomUUID());
        var middle = userStore.createUserGroup("middle-" + UUID.randomUUID(), top.id());
        var bottom = userStore.createUserGroup("bottom-" + UUID.randomUUID(), middle.id());

        assertThatThrownBy(() -> userStore.deleteUserGroup(top.id(), false))
                .isInstanceOf(UserStore.HasSubgroupsException.class)
                .hasMessageContaining("2 subgroups");
        assertThat(userStore.findUserGroupById(top.id())).isPresent();

        assertThat(userStore.deleteUserGroup(top.id(), true)).containsExactly(top.id(), middle.id(), bottom.id());
        assertThat(userStore.findUserGroupById(bottom.id())).isEmpty();
    }

    @Test
    void listUserGroupMembers_returnsOnlyMembersOfThatUserGroup() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());
        var member = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        var nonMember = userStore.createUser("Non-Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(member.id(), group.id());

        assertThat(userStore.listUserGroupMembers(group.id()))
                .extracting(UserStore.UserRow::id)
                .contains(member.id())
                .doesNotContain(nonMember.id());
    }

    @Test
    void removeUserFromUserGroup_removesMembership() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(user.id(), group.id());

        userStore.removeUserFromUserGroup(user.id(), group.id());

        assertThat(userStore.getUserGroupIdsForUser(user.id())).doesNotContain(group.id());
    }

    // --- The group tree ---

    @Test
    void aGroupCreatedWithoutAParent_goesUnderTheRoot() {
        var root = userStore.listUserGroups().stream().filter(g -> g.parentId() == null).findFirst().orElseThrow();

        assertThat(userStore.createUserGroup("group-" + UUID.randomUUID()).parentId()).isEqualTo(root.id());
    }

    @Test
    void thereIsOnlyOneRoot() {
        assertThatThrownBy(() -> userStore.createRootUserGroup("second-root-" + UUID.randomUUID()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void getUserGroupIdsForUser_includesEveryGroupAboveTheUsersGroups() {
        var grandparent = userStore.createUserGroup("grandparent-" + UUID.randomUUID());
        var parent = userStore.createUserGroup("parent-" + UUID.randomUUID(), grandparent.id());
        var child = userStore.createUserGroup("child-" + UUID.randomUUID(), parent.id());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(user.id(), child.id());

        assertThat(userStore.getUserGroupIdsForUser(user.id())).contains(child.id(), parent.id(), grandparent.id());
    }

    @Test
    void setParent_movesTheGroup_andWhatItsMembersInherit() {
        var oldParent = userStore.createUserGroup("old-" + UUID.randomUUID());
        var newParent = userStore.createUserGroup("new-" + UUID.randomUUID());
        var group = userStore.createUserGroup("group-" + UUID.randomUUID(), oldParent.id());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserToUserGroup(user.id(), group.id());

        userStore.setParent(group.id(), newParent.id());

        assertThat(userStore.findUserGroupById(group.id()).orElseThrow().parentId()).isEqualTo(newParent.id());
        assertThat(userStore.getUserGroupIdsForUser(user.id())).contains(newParent.id()).doesNotContain(oldParent.id());
    }

    @Test
    void setParent_refusesTheGroupItself() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());

        assertThatThrownBy(() -> userStore.setParent(group.id(), group.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setParent_refusesASubgroup_whichWouldMakeACycle() {
        var a = userStore.createUserGroup("a-" + UUID.randomUUID());
        var b = userStore.createUserGroup("b-" + UUID.randomUUID(), a.id());
        var c = userStore.createUserGroup("c-" + UUID.randomUUID(), b.id());

        assertThatThrownBy(() -> userStore.setParent(a.id(), c.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listMemberUserGroups_returnsOnlyDirectSubgroups() {
        var outer = userStore.createUserGroup("outer-" + UUID.randomUUID());
        var inner = userStore.createUserGroup("inner-" + UUID.randomUUID(), outer.id());
        userStore.createUserGroup("innermost-" + UUID.randomUUID(), inner.id());

        assertThat(userStore.listMemberUserGroups(outer.id())).containsExactly(inner);
    }

    // --- Local credentials ---

    @Test
    void createLocalCredentials_thenFindCredentialsByUserId_returnsThem() {
        var user = userStore.createUser("Local User", "user-" + UUID.randomUUID() + "@example.com", false);

        userStore.createLocalCredentials(user.id(), "hashed-password");

        var credentials = userStore.findCredentialsByUserId(user.id()).orElseThrow();
        assertThat(credentials.userId()).isEqualTo(user.id());
        assertThat(credentials.passwordHash()).isEqualTo("hashed-password");
    }

    @Test
    void findCredentialsByUserId_forAUserWithoutLocalCredentials_returnsEmpty() {
        var user = userStore.createUser("No Credentials", "user-" + UUID.randomUUID() + "@example.com", false);

        assertThat(userStore.findCredentialsByUserId(user.id())).isEmpty();
    }

    @Test
    void updatePasswordHash_persistsChange() {
        var user = userStore.createUser("Local User", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.createLocalCredentials(user.id(), "old-hash");

        userStore.updatePasswordHash(user.id(), "new-hash");

        assertThat(userStore.findCredentialsByUserId(user.id()).orElseThrow().passwordHash()).isEqualTo("new-hash");
    }

    // --- Personal access tokens ---

    @Test
    void listTokensForUser_returnsOnlyThatUsersTokens() {
        var user = userStore.createUser("Token User", "user-" + UUID.randomUUID() + "@example.com", false);
        var otherUser = userStore.createUser("Other User", "user-" + UUID.randomUUID() + "@example.com", false);
        UUID tokenId = userStore.createPersonalAccessToken(user.id(), "token-hash-" + UUID.randomUUID(), "My Token", null);
        userStore.createPersonalAccessToken(otherUser.id(), "token-hash-" + UUID.randomUUID(), "Other Token", OffsetDateTime.now().plusDays(1));

        assertThat(userStore.listTokensForUser(user.id()))
                .extracting(UserStore.PersonalAccessTokenRow::id)
                .containsExactly(tokenId);
    }
}
