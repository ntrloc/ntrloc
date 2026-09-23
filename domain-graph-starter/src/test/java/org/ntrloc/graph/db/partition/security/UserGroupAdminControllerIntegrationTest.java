package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers UserGroupAdminController's group CRUD and membership endpoints. Reuses
// AuthorizationTestDataInitializer's standing fixture (root=superuser, alice=non-admin) for
// admin-check tests -- see AccessAdminControllerIntegrationTest's own comment on why that's safe
// to read without risking cross-test contamination. Every created group/user below gets a
// UUID-suffixed name/externalId, so nothing here collides with that fixture or with other test
// methods sharing the same singleton Postgres container (see AbstractIntegrationTest's own
// comment).
class UserGroupAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private DefaultUserGroupInitializer defaultUserGroupInitializer;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAsRoot(String uri) {
        return (List<Map<String, Object>>) (List<?>) webTestClient.get().uri(uri)
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody();
    }

    // --- List ---

    @Test
    void listUserGroups_asAdmin_includesMemberCounts() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var member = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserToUserGroup(member.id(), group.id());

        List<Map<String, Object>> groups = getAsRoot("/api/admin/user-groups");

        assertThat(groups).filteredOn(g -> g.get("id").equals(group.id().toString()))
                .extracting(g -> g.get("memberCount")).containsExactly(1);
    }

    @Test
    void listUserGroups_asNonAdmin_isForbidden() {
        webTestClient.get().uri("/api/admin/user-groups")
                .header("X-Ntrloc-User", "alice")
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Create ---

    @Test
    void createUserGroup_asAdmin_createsIt() {
        String name = "group-" + UUID.randomUUID();

        webTestClient.post().uri("/api/admin/user-groups")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "parentUserGroupId", defaultUserGroupInitializer.getDefaultUserGroupId()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo(name)
                .jsonPath("$.memberCount").isEqualTo(0);

        assertThat(securityRepo.findUserGroupByName(name)).isPresent();
    }

    @Test
    void createUserGroup_withABlankName_returnsBadRequest() {
        webTestClient.post().uri("/api/admin/user-groups")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "   "))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createUserGroup_withANameThatAlreadyExists_returnsConflict() {
        String name = "group-" + UUID.randomUUID();
        securityRepo.createUserGroup(name);

        webTestClient.post().uri("/api/admin/user-groups")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "parentUserGroupId", defaultUserGroupInitializer.getDefaultUserGroupId()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    // --- Update ---

    @Test
    void updateUserGroup_asAdmin_persistsTheNewName() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        String newName = "renamed-" + UUID.randomUUID();

        webTestClient.put().uri("/api/admin/user-groups/" + group.id())
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", newName))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo(newName);

        assertThat(securityRepo.findUserGroupById(group.id())).isPresent().get()
                .satisfies(g -> assertThat(g.name()).isEqualTo(newName));
    }

    @Test
    void updateUserGroup_forAnUnknownUserGroup_returnsNotFound() {
        webTestClient.put().uri("/api/admin/user-groups/" + UUID.randomUUID())
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "new-name"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void updateUserGroup_withABlankName_returnsBadRequest() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        webTestClient.put().uri("/api/admin/user-groups/" + group.id())
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- Delete ---

    @Test
    void deleteUserGroup_asAdmin_removesIt() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());

        webTestClient.delete().uri("/api/admin/user-groups/" + group.id())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(securityRepo.findUserGroupById(group.id())).isEmpty();
    }

    @Test
    void deleteUserGroup_forAnUnknownUserGroup_returnsNotFound() {
        webTestClient.delete().uri("/api/admin/user-groups/" + UUID.randomUUID())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteUserGroup_forTheDefaultUserGroup_isForbidden() {
        UUID defaultUserGroupId = defaultUserGroupInitializer.getDefaultUserGroupId();

        webTestClient.delete().uri("/api/admin/user-groups/" + defaultUserGroupId)
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Members ---

    @Test
    void listMembers_asAdmin_returnsEveryMember() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var member = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserToUserGroup(member.id(), group.id());

        List<Map<String, Object>> members = getAsRoot("/api/admin/user-groups/" + group.id() + "/members");

        assertThat(members).extracting(m -> m.get("id")).containsExactly(member.id().toString());
    }

    @Test
    void listMembers_forAnUnknownUserGroup_returnsNotFound() {
        webTestClient.get().uri("/api/admin/user-groups/" + UUID.randomUUID() + "/members")
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void addMember_asAdmin_addsTheUserToTheUserGroup() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "New Member", null, false);

        webTestClient.post().uri("/api/admin/user-groups/" + group.id() + "/members")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", user.id().toString()))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).contains(group.id());
    }

    @Test
    void addMember_forAnUnknownUserGroup_returnsNotFound() {
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "New Member", null, false);

        webTestClient.post().uri("/api/admin/user-groups/" + UUID.randomUUID() + "/members")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", user.id().toString()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void removeMember_asAdmin_removesTheUserFromTheUserGroup() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);
        securityRepo.addUserToUserGroup(user.id(), group.id());

        webTestClient.delete().uri("/api/admin/user-groups/" + group.id() + "/members/" + user.id())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(securityRepo.getUserGroupIdsForUser(user.id())).doesNotContain(group.id());
    }

    @Test
    void removeMember_asNonAdmin_isForbidden() {
        var group = securityRepo.createUserGroup("group-" + UUID.randomUUID());
        var user = securityRepo.createUser("user-" + UUID.randomUUID(), "Member", null, false);

        webTestClient.delete().uri("/api/admin/user-groups/" + group.id() + "/members/" + user.id())
                .header("X-Ntrloc-User", "alice")
                .exchange()
                .expectStatus().isForbidden();
    }
}
