package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Requests under /api/drafts/{draftId}/...: only admins and the draft's participants get in; everyone
// else gets the same 404 as for a draft that doesn't exist.
class DraftModeIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";
    private static final String UNINVITED = "bob@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    private String draftId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraftWithAliceInvited() {
        discardOpenDrafts();
        var draft = webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft mode test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        draftId = (String) draft.get("id");
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        webTestClient.post().uri("/api/drafts/" + draftId + "/participants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("userId", alice)).exchange().expectStatus().isNoContent();
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void discardOpenDrafts() {
        List<Map<String, Object>> open = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        for (var draft : open) {
            webTestClient.post().uri("/api/drafts/" + draft.get("id") + "/discard").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                    .exchange().expectStatus().isOk();
        }
    }

    private WebTestClient.ResponseSpec get(String uri, String as) {
        return webTestClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as)).exchange();
    }

    @Test
    void anAdmin_andAnInvitedUser_canEnterTheDraft() {
        get("/api/drafts/" + draftId, ADMIN).expectStatus().isOk();
        get("/api/drafts/" + draftId, INVITED).expectStatus().isOk();
    }

    @Test
    void anUninvitedUser_cantTellTheDraftExists() {
        get("/api/drafts/" + draftId, UNINVITED).expectStatus().isNotFound();
        get("/api/drafts/" + draftId + "/admin/grants/targets", UNINVITED).expectStatus().isNotFound();
    }

    @Test
    void aClosedOrMadeUpDraft_isNotFound() {
        get("/api/drafts/" + UUID.randomUUID(), ADMIN).expectStatus().isNotFound();
        get("/api/drafts/not-a-draft", ADMIN).expectStatus().isNotFound();

        webTestClient.post().uri("/api/drafts/" + draftId + "/discard").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk();
        get("/api/drafts/" + draftId + "/admin/grants/targets", ADMIN).expectStatus().isNotFound();
    }

    // Participation changes where a user works, not what they may do: the admin API stays admin-only.
    @Test
    void anInvitedNonAdmin_stillCantUseTheAdminApi() {
        get("/api/drafts/" + draftId + "/admin/grants/targets", INVITED).expectStatus().isForbidden();
        UUID viewers = userGroupStore.findUserGroupByName("viewers").orElseThrow().id();
        webTestClient.put().uri("/api/drafts/" + draftId + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(INVITED))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", viewers),
                        "permission", Map.of("kind", "itemType", "itemTypeId", UUID.randomUUID(), "op", "read"), "value", true))
                .exchange().expectStatus().isForbidden();
    }
}
