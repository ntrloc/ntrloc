package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The draft registry's API. Phase 1 allows one open draft at a time, so each test starts and ends with none open.
class DraftControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @BeforeEach
    @AfterEach
    void discardOpenDrafts() {
        for (var draft : listAs(ADMIN)) {
            post("/api/drafts/" + draft.get("id") + "/discard", ADMIN, Map.of()).expectStatus().isOk();
        }
    }

    private WebTestClient.ResponseSpec post(String uri, String as, Object body) {
        return webTestClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as)).bodyValue(body).exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAs(String email) {
        return (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(email))
                .exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> start(String name) {
        return post("/api/drafts", ADMIN, Map.of("name", name)).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private UUID newNonAdmin() {
        return userStore.createUser("Draft participant", "draft-" + UUID.randomUUID() + "@example.com", false).id();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAdminStartsADraft_andSeesItListed() {
        var draft = start("Q3 access review");

        assertThat(draft.get("status")).isEqualTo("OPEN");
        assertThat(((Map<String, Object>) draft.get("createdBy")).get("displayName")).isEqualTo("Root (admin)");
        assertThat(listAs(ADMIN)).extracting(d -> d.get("id")).containsExactly(draft.get("id"));
    }

    @Test
    void onlyOneDraftMayBeOpen() {
        start("First");

        post("/api/drafts", ADMIN, Map.of("name", "Second")).expectStatus().isEqualTo(409);
    }

    @Test
    void aDraftNeedsAName() {
        post("/api/drafts", ADMIN, Map.of("name", "  ")).expectStatus().isBadRequest();
    }

    @Test
    void aNonAdmin_seesOnlyTheDraftsTheyreInvitedTo() {
        var draft = start("Invite test");
        assertThat(listAs("alice@example.com")).isEmpty();

        UUID alice = userStore.findUserByEmail("alice@example.com").orElseThrow().id();
        post("/api/drafts/" + draft.get("id") + "/participants", ADMIN, Map.of("userId", alice)).expectStatus().isNoContent();

        assertThat(listAs("alice@example.com")).extracting(d -> d.get("id")).containsExactly(draft.get("id"));
        webTestClient.delete().uri("/api/drafts/" + draft.get("id") + "/participants/" + alice)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isNoContent();
        assertThat(listAs("alice@example.com")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void participants_listTheInvitedUsers() {
        var draft = start("Participants test");
        UUID user = newNonAdmin();
        post("/api/drafts/" + draft.get("id") + "/participants", ADMIN, Map.of("userId", user)).expectStatus().isNoContent();

        List<Map<String, Object>> participants = (List<Map<String, Object>>) (List<?>) webTestClient.get()
                .uri("/api/drafts/" + draft.get("id") + "/participants")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(participants).extracting(p -> p.get("id")).containsExactly(user.toString());
    }

    @Test
    void admins_areNeverInvited() {
        var draft = start("Admin invite test");
        UUID root = userStore.findUserByEmail(ADMIN).orElseThrow().id();

        post("/api/drafts/" + draft.get("id") + "/participants", ADMIN, Map.of("userId", root)).expectStatus().isBadRequest();
    }

    @Test
    void nonAdmins_cantStartInviteOrDiscard() {
        var draft = start("Permissions test");

        post("/api/drafts", "alice@example.com", Map.of("name", "Mine")).expectStatus().isForbidden();
        post("/api/drafts/" + draft.get("id") + "/participants", "alice@example.com", Map.of("userId", newNonAdmin())).expectStatus().isForbidden();
        post("/api/drafts/" + draft.get("id") + "/discard", "alice@example.com", Map.of()).expectStatus().isForbidden();
    }

    @Test
    void aDiscardedDraft_isGone_andANewOneMayStart() {
        var draft = start("To discard");

        post("/api/drafts/" + draft.get("id") + "/discard", ADMIN, Map.of()).expectStatus().isOk();

        assertThat(listAs(ADMIN)).isEmpty();
        post("/api/drafts/" + draft.get("id") + "/discard", ADMIN, Map.of()).expectStatus().isNotFound();
        start("Next");
    }
}
