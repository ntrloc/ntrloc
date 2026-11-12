package org.ntrloc.graph.principal;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A signed-in session keeps the principal it signed in with; the resolver answers with the user as they
// are now (docs/ntrloc-drafts-plan.md step 2.3), so a change to their groups, admin flag or existence
// applies on their next request, not their next sign-in.
class PrincipalFreshnessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ExternalPrincipalResolver principals;

    @Autowired
    private UserService userService;

    @Autowired
    private UserGroupStore userGroupStore;

    private UUID root() {
        return userGroupStore.findUserGroupByName("everyone").orElseThrow().id();
    }

    // What a session holds after sign-in: the principal as it was then.
    private Authentication signedIn(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(principals.byEmail(email).orElseThrow(), null, List.of());
    }

    private String newUser() {
        String email = "fresh-" + UUID.randomUUID() + "@example.com";
        userService.createUser("Fresh", email, false);
        return email;
    }

    @Test
    void beingMadeAnAdmin_appliesOnTheNextRequest() {
        String email = newUser();
        Authentication session = signedIn(email);
        assertThat(principals.resolve(session).isAdmin()).isFalse();

        userService.updateUser(principals.resolve(session).id(), "Fresh", email, true);

        assertThat(principals.resolve(session).isAdmin()).isTrue();
    }

    @Test
    void joiningAGroup_appliesOnTheNextRequest_withTheGroupsAboveIt() {
        String email = newUser();
        Authentication session = signedIn(email);
        UUID parent = userService.createUserGroup("parent-" + UUID.randomUUID(), root()).id();
        UUID child = userService.createUserGroup("child-" + UUID.randomUUID(), parent).id();

        userService.addMember(principals.resolve(session).id(), child);

        assertThat(principals.resolve(session).groupIds()).contains(child, parent);
    }

    @Test
    void aGroupMovedAboveThem_appliesOnTheNextRequest() {
        String email = newUser();
        Authentication session = signedIn(email);
        UUID group = userService.createUserGroup("moving-" + UUID.randomUUID(), root()).id();
        UUID newParent = userService.createUserGroup("new-parent-" + UUID.randomUUID(), root()).id();
        userService.addMember(principals.resolve(session).id(), group);
        assertThat(principals.resolve(session).groupIds()).doesNotContain(newParent);

        userService.setParent(group, newParent);

        assertThat(principals.resolve(session).groupIds()).contains(newParent);
    }

    @Test
    void aDeletedUsersSession_stopsResolving() {
        String email = newUser();
        Authentication session = signedIn(email);

        userService.deleteUser(principals.resolve(session).id());

        assertThat(principals.fromAuthentication(session)).isEmpty();
        assertThatThrownBy(() -> principals.resolve(session)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }
}
