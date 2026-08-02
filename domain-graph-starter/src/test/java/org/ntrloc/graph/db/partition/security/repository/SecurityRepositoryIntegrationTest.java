package org.ntrloc.graph.db.partition.security.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers SecurityRepository's admin-facing listing/update/delete methods -- the ones
// AuthorizationTestDataInitializer/AccessAdminControllerIntegrationTest's fixture setup never
// exercises, since that only ever creates users/groups and reads them back, never lists, updates,
// or deletes. security_user/security_group are shared tables, so every test here gives its own
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

        securityRepo.updateUser(user.id(), "Updated Name", "updated@example.com", true);

        var reloaded = securityRepo.findUserByExternalId(user.externalId()).orElseThrow();
        assertThat(reloaded.displayName()).isEqualTo("Updated Name");
        assertThat(reloaded.email()).isEqualTo("updated@example.com");
        assertThat(reloaded.isSuperuser()).isTrue();
    }

    // --- Groups ---

    @Test
    void listGroups_includesCreatedGroups() {
        var group = securityRepo.createGroup("group-" + UUID.randomUUID());

        assertThat(securityRepo.listGroups()).contains(group);
    }

    @Test
    void updateGroup_persistsNewName() {
        var group = securityRepo.createGroup("group-" + UUID.randomUUID());

        securityRepo.updateGroup(group.id(), "renamed-" + UUID.randomUUID());

        assertThat(securityRepo.findGroupById(group.id())).isPresent().get()
                .satisfies(g -> assertThat(g.name()).startsWith("renamed-"));
    }

    @Test
    void deleteGroup_removesIt() {
        var group = securityRepo.createGroup("group-" + UUID.randomUUID());

        securityRepo.deleteGroup(group.id());

        assertThat(securityRepo.findGroupById(group.id())).isEmpty();
    }

    @Test
    void listGroupMembers_returnsOnlyMembersOfThatGroup() {
        var group = securityRepo.createGroup("group-" + UUID.randomUUID());
        var member = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        var nonMember = securityRepo.createUser("user-" + UUID.randomUUID(), "Non-Member", null, false);
        securityRepo.addUserToGroup(member.id(), group.id());

        assertThat(securityRepo.listGroupMembers(group.id()))
                .extracting(SecurityRepository.UserRow::id)
                .contains(member.id())
                .doesNotContain(nonMember.id());
    }

    @Test
    void removeUserFromGroup_removesMembership() {
        var group = securityRepo.createGroup("group-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserToGroup(user.id(), group.id());

        securityRepo.removeUserFromGroup(user.id(), group.id());

        assertThat(securityRepo.getGroupIdsForUser(user.id())).doesNotContain(group.id());
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
