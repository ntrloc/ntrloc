package org.ntrloc.graph.db.partition.security.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers SecurityRepository's admin-facing listing/update/delete methods -- the ones
// AuthorizationTestDataInitializer/AccessAdminControllerIntegrationTest's fixture setup never
// exercises, since that only ever creates users/groups and reads them back, never lists, updates,
// or deletes. security_user/security_user_group are shared tables, so every test here gives its own
// rows a UUID-suffixed externalId/name/email and filters on that, never a shared literal.
class SecurityRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityRepository securityRepo;

    // --- Users ---

    @Test
    void listUsers_includesCreatedUsers() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Display Name", null, false);

        assertThat(securityRepo.listUsers()).contains(user);
    }

    @Test
    void updateUser_persistsChanges() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Original Name", "original@example.com", false);

        securityRepo.updateUser(user.id(), user.externalId(), "Updated Name", "updated@example.com", true);

        var reloaded = securityRepo.findUserByExternalId(user.externalId()).orElseThrow();
        assertThat(reloaded.displayName()).isEqualTo("Updated Name");
        assertThat(reloaded.email()).isEqualTo("updated@example.com");
        assertThat(reloaded.isSuperuser()).isTrue();
    }

    // --- UserGroups ---

    @Test
    void listUserGroups_includesCreatedUserGroups() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        assertThat(securityRepo.listUserGroups()).contains(group);
    }

    @Test
    void updateUserGroup_persistsNewName() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        securityRepo.updateUserGroup(group.id(), "renamed-" + UUID.randomUUID());

        assertThat(securityRepo.findUserGroupById(group.id())).isPresent().get()
                .satisfies(g -> assertThat(g.name()).startsWith("renamed-"));
    }

    @Test
    void deleteUserGroup_removesIt() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        securityRepo.deleteUserGroup(group.id());

        assertThat(securityRepo.findUserGroupById(group.id())).isEmpty();
    }

    @Test
    void listUserGroupMembers_returnsOnlyMembersOfThatUserGroup() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var member = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        var nonMember = securityRepo.createUser("user-" + UUID.randomUUID(), "Non-Member", null, false);
        securityRepo.addUserToUserGroup(member.id(), group.id());

        assertThat(securityRepo.listUserGroupMembers(group.id()))
                .extracting(SecurityRepository.UserRow::id)
                .contains(member.id())
                .doesNotContain(nonMember.id());
    }

    @Test
    void removeUserFromUserGroup_removesMembership() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserToUserGroup(user.id(), group.id());

        securityRepo.removeUserFromUserGroup(user.id(), group.id());

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).doesNotContain(group.id());
    }

    // --- UserGroup nesting ---

    @Test
    void getUserGroupIdsForUser_includesUserGroupsTransitivelyContainingTheUsersDirectUserGroup() {
        var outer = securityRepo.createUserGroup("outer-" + UUID.randomUUID());
        var inner = securityRepo.createUserGroup("inner-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserGroupToUserGroup(inner.id(), outer.id());
        securityRepo.addUserToUserGroup(user.id(), inner.id());

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).contains(inner.id(), outer.id());
    }

    @Test
    void getUserGroupIdsForUser_resolvesMultipleLevelsOfNesting() {
        var grandparent = securityRepo.createUserGroup("grandparent-" + UUID.randomUUID());
        var parent = securityRepo.createUserGroup("parent-" + UUID.randomUUID());
        var child = securityRepo.createUserGroup("child-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserGroupToUserGroup(parent.id(), grandparent.id());
        securityRepo.addUserGroupToUserGroup(child.id(), parent.id());
        securityRepo.addUserToUserGroup(user.id(), child.id());

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).contains(child.id(), parent.id(), grandparent.id());
    }

    @Test
    void addUserGroupToUserGroup_rejectsSelfMembership() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        assertThatThrownBy(() -> securityRepo.addUserGroupToUserGroup(group.id(), group.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addUserGroupToUserGroup_rejectsACycle() {
        var a = securityRepo.createUserGroup("a-" + UUID.randomUUID());
        var b = securityRepo.createUserGroup("b-" + UUID.randomUUID());
        securityRepo.addUserGroupToUserGroup(a.id(), b.id()); // a is a member of b

        assertThatThrownBy(() -> securityRepo.addUserGroupToUserGroup(b.id(), a.id())) // b member of a would close the loop
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addUserGroupToUserGroup_rejectsAMultiLevelCycle() {
        var a = securityRepo.createUserGroup("a-" + UUID.randomUUID());
        var b = securityRepo.createUserGroup("b-" + UUID.randomUUID());
        var c = securityRepo.createUserGroup("c-" + UUID.randomUUID());
        securityRepo.addUserGroupToUserGroup(a.id(), b.id()); // a member of b
        securityRepo.addUserGroupToUserGroup(b.id(), c.id()); // b member of c, so a is transitively a member of c

        assertThatThrownBy(() -> securityRepo.addUserGroupToUserGroup(c.id(), a.id())) // c member of a would close a->b->c->a
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listMemberUserGroups_returnsOnlyDirectChildren() {
        var outer = securityRepo.createUserGroup("outer-" + UUID.randomUUID());
        var inner = securityRepo.createUserGroup("inner-" + UUID.randomUUID());
        var innermost = securityRepo.createUserGroup("innermost-" + UUID.randomUUID());
        securityRepo.addUserGroupToUserGroup(inner.id(), outer.id());
        securityRepo.addUserGroupToUserGroup(innermost.id(), inner.id());

        // innermost is a transitive, not direct, member of outer -- must not appear here.
        assertThat(securityRepo.listMemberUserGroups(outer.id())).containsExactly(inner);
    }

    @Test
    void listContainingUserGroups_returnsOnlyDirectParents() {
        var outer = securityRepo.createUserGroup("outer-" + UUID.randomUUID());
        var inner = securityRepo.createUserGroup("inner-" + UUID.randomUUID());
        securityRepo.addUserGroupToUserGroup(inner.id(), outer.id());

        assertThat(securityRepo.listContainingUserGroups(inner.id())).containsExactly(outer);
    }

    @Test
    void removeUserGroupFromUserGroup_removesTransitiveMembershipEffect() {
        var outer = securityRepo.createUserGroup("outer-" + UUID.randomUUID());
        var inner = securityRepo.createUserGroup("inner-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserGroupToUserGroup(inner.id(), outer.id());
        securityRepo.addUserToUserGroup(user.id(), inner.id());

        securityRepo.removeUserGroupFromUserGroup(inner.id(), outer.id());

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).contains(inner.id()).doesNotContain(outer.id());
    }

    // --- Local credentials ---

    @Test
    void createLocalCredentials_thenFindCredentialsByEmail_returnsThem() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Local User", null, false);
        String email = "local-" + UUID.randomUUID() + "@example.com";

        securityRepo.createLocalCredentials(user.id(), email, "hashed-password", "USER");

        var credentials = securityRepo.findCredentialsByEmail(email).orElseThrow();
        assertThat(credentials.userId()).isEqualTo(user.id());
        assertThat(credentials.passwordHash()).isEqualTo("hashed-password");
        assertThat(credentials.role()).isEqualTo("USER");
    }

    @Test
    void findCredentialsByEmail_forUnknownEmail_returnsEmpty() {
        assertThat(securityRepo.findCredentialsByEmail("nobody-" + UUID.randomUUID() + "@example.com")).isEmpty();
    }

    @Test
    void updatePasswordHash_persistsChange() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Local User", null, false);
        String email = "local-" + UUID.randomUUID() + "@example.com";
        securityRepo.createLocalCredentials(user.id(), email, "old-hash", "USER");

        securityRepo.updatePasswordHash(user.id(), "new-hash");

        assertThat(securityRepo.findCredentialsByEmail(email).orElseThrow().passwordHash()).isEqualTo("new-hash");
    }

    @Test
    void updateLocalCredentialsRole_persistsChange() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Local User", null, false);
        String email = "local-" + UUID.randomUUID() + "@example.com";
        securityRepo.createLocalCredentials(user.id(), email, "hash", "USER");

        securityRepo.updateLocalCredentialsRole(user.id(), "ADMIN");

        assertThat(securityRepo.findCredentialsByEmail(email).orElseThrow().role()).isEqualTo("ADMIN");
    }

    // --- Personal access tokens ---

    @Test
    void listTokensForUser_returnsOnlyThatUsersTokens() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Token User", null, false);
        var otherUser = securityRepo.createUser("user-" + UUID.randomUUID(), "Other User", null, false);
        UUID tokenId = securityRepo.createPersonalAccessToken(user.id(), "token-hash-" + UUID.randomUUID(), "My Token", null);
        securityRepo.createPersonalAccessToken(otherUser.id(), "token-hash-" + UUID.randomUUID(), "Other Token", OffsetDateTime.now().plusDays(1));

        assertThat(securityRepo.listTokensForUser(user.id()))
                .extracting(SecurityRepository.PersonalAccessTokenRow::id)
                .containsExactly(tokenId);
    }
}
