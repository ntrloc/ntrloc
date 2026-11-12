package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// What a draft changes against live (GET /api/drafts/{id}/changes), which the UI marks with modified
// dots. Each test works on a fresh group, so nothing one test grants reaches another.
class DraftChangesIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID draftId;
    private UUID group;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraftWithAliceInvited() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft changes test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        webTestClient.post().uri("/api/drafts/" + draftId + "/participants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("userId", alice)).exchange().expectStatus().isNoContent();
        group = userGroupStore.createUserGroup("draft-changes-" + UUID.randomUUID()).id();
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

    private Permission createProducts() {
        return Permission.itemType(fixture.productTypeId(), "create");
    }

    private Permission readProducts() {
        return Permission.itemType(fixture.productTypeId(), "read");
    }

    private void setInDraft(Permission permission, boolean value) {
        var body = new HashMap<String, Object>();
        body.put("kind", permission.kind());
        body.put("itemTypeId", permission.itemTypeId());
        body.put("op", permission.op());
        webTestClient.put().uri("/api/drafts/" + draftId + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", group), "permission", body, "value", value))
                .exchange().expectStatus().isNoContent();
    }

    private WebTestClient.ResponseSpec getChanges(String as) {
        return webTestClient.get().uri("/api/drafts/" + draftId + "/changes").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as)).exchange();
    }

    // Each changed grant as "kind:id op value".
    @SuppressWarnings("unchecked")
    private List<String> changes() {
        Map<String, Object> body = getChanges(ADMIN).expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) body.get("grants")).stream().map(g -> {
            var principal = (Map<String, Object>) g.get("principal");
            var permission = (Map<String, Object>) g.get("permission");
            return principal.get("kind") + ":" + principal.get("id") + " " + permission.get("op") + " " + g.get("value");
        }).toList();
    }

    @Test
    void aNewDraftChangesNothing() {
        assertThat(changes()).isEmpty();
    }

    @Test
    void eachGrantChangedInTheDraft_isListed_withItsValue() {
        accessService.setPermission("USER_GROUP", group, readProducts(), true);
        setInDraft(createProducts(), true);
        setInDraft(readProducts(), false);

        assertThat(changes()).containsExactlyInAnyOrder("group:" + group + " create true", "group:" + group + " read false");
    }

    @Test
    void aGrantSetBackToItsLiveValue_isNoLongerAChange() {
        setInDraft(createProducts(), true);
        setInDraft(createProducts(), false);

        assertThat(changes()).isEmpty();
    }

    @Test
    void aGrantLiveHasSinceCaughtUpWith_isNoLongerAChange() {
        setInDraft(createProducts(), true);
        accessService.setPermission("USER_GROUP", group, createProducts(), true);

        assertThat(changes()).isEmpty();
    }

    @Test
    void onlyAdminsSeeGrantChanges() {
        getChanges(INVITED).expectStatus().isForbidden();
    }
}
