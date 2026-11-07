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

        userStore.deleteUserGroup(group.id());

        assertThat(userStore.findUserGroupById(group.id())).isEmpty();
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

    // --- UserGroup nesting ---

    @Test
    void getUserGroupIdsForUser_includesUserGroupsTransitivelyContainingTheUsersDirectUserGroup() {
        var outer = userStore.createUserGroup("outer-" + UUID.randomUUID());
        var inner = userStore.createUserGroup("inner-" + UUID.randomUUID());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserGroupToUserGroup(inner.id(), outer.id());
        userStore.addUserToUserGroup(user.id(), inner.id());

        assertThat(userStore.getUserGroupIdsForUser(user.id())).contains(inner.id(), outer.id());
    }

    @Test
    void getUserGroupIdsForUser_resolvesMultipleLevelsOfNesting() {
        var grandparent = userStore.createUserGroup("grandparent-" + UUID.randomUUID());
        var parent = userStore.createUserGroup("parent-" + UUID.randomUUID());
        var child = userStore.createUserGroup("child-" + UUID.randomUUID());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserGroupToUserGroup(parent.id(), grandparent.id());
        userStore.addUserGroupToUserGroup(child.id(), parent.id());
        userStore.addUserToUserGroup(user.id(), child.id());

        assertThat(userStore.getUserGroupIdsForUser(user.id())).contains(child.id(), parent.id(), grandparent.id());
    }

    @Test
    void addUserGroupToUserGroup_rejectsSelfMembership() {
        var group = userStore.createUserGroup("group-" + UUID.randomUUID());

        assertThatThrownBy(() -> userStore.addUserGroupToUserGroup(group.id(), group.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addUserGroupToUserGroup_rejectsACycle() {
        var a = userStore.createUserGroup("a-" + UUID.randomUUID());
        var b = userStore.createUserGroup("b-" + UUID.randomUUID());
        userStore.addUserGroupToUserGroup(a.id(), b.id()); // a is a member of b

        assertThatThrownBy(() -> userStore.addUserGroupToUserGroup(b.id(), a.id())) // b member of a would close the loop
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addUserGroupToUserGroup_rejectsAMultiLevelCycle() {
        var a = userStore.createUserGroup("a-" + UUID.randomUUID());
        var b = userStore.createUserGroup("b-" + UUID.randomUUID());
        var c = userStore.createUserGroup("c-" + UUID.randomUUID());
        userStore.addUserGroupToUserGroup(a.id(), b.id()); // a member of b
        userStore.addUserGroupToUserGroup(b.id(), c.id()); // b member of c, so a is transitively a member of c

        assertThatThrownBy(() -> userStore.addUserGroupToUserGroup(c.id(), a.id())) // c member of a would close a->b->c->a
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listMemberUserGroups_returnsOnlyDirectChildren() {
        var outer = userStore.createUserGroup("outer-" + UUID.randomUUID());
        var inner = userStore.createUserGroup("inner-" + UUID.randomUUID());
        var innermost = userStore.createUserGroup("innermost-" + UUID.randomUUID());
        userStore.addUserGroupToUserGroup(inner.id(), outer.id());
        userStore.addUserGroupToUserGroup(innermost.id(), inner.id());

        // innermost is a transitive, not direct, member of outer -- must not appear here.
        assertThat(userStore.listMemberUserGroups(outer.id())).containsExactly(inner);
    }

    @Test
    void listContainingUserGroups_returnsOnlyDirectParents() {
        var outer = userStore.createUserGroup("outer-" + UUID.randomUUID());
        var inner = userStore.createUserGroup("inner-" + UUID.randomUUID());
        userStore.addUserGroupToUserGroup(inner.id(), outer.id());

        assertThat(userStore.listContainingUserGroups(inner.id())).containsExactly(outer);
    }

    @Test
    void removeUserGroupFromUserGroup_removesTransitiveMembershipEffect() {
        var outer = userStore.createUserGroup("outer-" + UUID.randomUUID());
        var inner = userStore.createUserGroup("inner-" + UUID.randomUUID());
        var user = userStore.createUser("Member", "user-" + UUID.randomUUID() + "@example.com", false);
        userStore.addUserGroupToUserGroup(inner.id(), outer.id());
        userStore.addUserToUserGroup(user.id(), inner.id());

        userStore.removeUserGroupFromUserGroup(inner.id(), outer.id());

        assertThat(userStore.getUserGroupIdsForUser(user.id())).contains(inner.id()).doesNotContain(outer.id());
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
