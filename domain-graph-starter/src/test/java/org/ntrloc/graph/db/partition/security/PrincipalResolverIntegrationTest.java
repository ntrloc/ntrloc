package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.principal.InternalPrincipalResolver;

import org.ntrloc.graph.principal.ExternalPrincipalResolver;

import org.ntrloc.graph.principal.NtrlocPrincipal;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The three ways a principal is resolved: by email and by personal access token (requests), and by
// user id (stored references). Each must load the user's current state -- groups and admin
// flag -- straight from security_user, never a cached copy.
class PrincipalResolverIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ExternalPrincipalResolver external;

    @Autowired
    private InternalPrincipalResolver internal;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private PersonalAccessTokenService tokenService;

    private UserStore.UserRow createUserInAGroup() {
        var user = userStore.createUser("Resolver User", "Resolver-" + UUID.randomUUID() + "@Example.com", false);
        var group = userGroupStore.createUserGroup("resolver-group-" + UUID.randomUUID());
        userGroupStore.addUserToUserGroup(user.id(), group.id());
        return user;
    }

    // --- By email ---

    @Test
    void byEmail_resolvesTheUserWithTheirGroups_ignoringCase() {
        var user = createUserInAGroup();

        NtrlocPrincipal principal = external.byEmail(user.email().toLowerCase()).orElseThrow();

        assertThat(principal.id()).isEqualTo(user.id());
        assertThat(principal.email()).isEqualTo(user.email());
        assertThat(principal.groupIds()).isEqualTo(userGroupStore.getUserGroupIdsForUser(user.id()));
        assertThat(principal.isAdmin()).isFalse();
    }

    @Test
    void byEmail_forAnUnknownEmail_isEmpty() {
        assertThat(external.byEmail("nobody-" + UUID.randomUUID() + "@example.com")).isEmpty();
    }

    // --- By token ---

    @Test
    void byToken_resolvesTheTokensOwner() {
        var user = createUserInAGroup();
        var issued = tokenService.issueForUser(user.id(), "resolver-test", null);

        assertThat(external.byToken(issued.rawToken())).map(NtrlocPrincipal::id).contains(user.id());
    }

    @Test
    void byToken_forAnUnknownToken_isEmpty() {
        assertThat(external.byToken("not-a-real-token")).isEmpty();
    }

    @Test
    void byToken_forAnExpiredToken_isEmpty() {
        var user = createUserInAGroup();
        var issued = tokenService.issueForUser(user.id(), "already-expired", -1);

        assertThat(external.byToken(issued.rawToken())).isEmpty();
    }

    // --- By id ---

    @Test
    void byId_resolvesTheUser() {
        var user = createUserInAGroup();

        assertThat(internal.byId(user.id())).map(NtrlocPrincipal::email).contains(user.email());
    }

    @Test
    void byId_forAnUnknownId_isEmpty() {
        assertThat(internal.byId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void byId_reflectsTheUsersCurrentState_notASnapshot() {
        var user = createUserInAGroup();
        assertThat(internal.byId(user.id()).orElseThrow().isAdmin()).isFalse();

        userStore.updateUser(user.id(), user.displayName(), user.email(), true);

        assertThat(internal.byId(user.id()).orElseThrow().isAdmin()).isTrue();
    }
}
