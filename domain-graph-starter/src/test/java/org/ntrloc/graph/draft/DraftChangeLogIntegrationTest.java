package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.changelog.ChangeEntries.Entry;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The draft changelog (docs/ntrloc-system-model-notes.md §13.6): what happens in a draft, who did it and
// when -- in its own table, so nothing done in a draft reaches live's log until it's applied, and then
// live's entries say which draft they came from.
class DraftChangeLogIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DraftChangeLog draftLog;

    @Autowired
    private ChangeLog changeLog;

    @Autowired
    private UserStore userStore;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID draftId;
    private UUID group;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraft() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) post("/api/drafts", Map.of("name", "Draft log test")).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
        group = userStore.createUserGroup("draft-log-" + UUID.randomUUID()).id();
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void discardOpenDrafts() {
        List<Map<String, Object>> open = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        for (var draft : open) post("/api/drafts/" + draft.get("id") + "/discard", null).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec post(String uri, Object body) {
        var request = webTestClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN));
        return body == null ? request.exchange() : request.bodyValue(body).exchange();
    }

    private Permission createProducts() {
        return Permission.itemType(fixture.productTypeId(), "create");
    }

    private static Map<String, Object> body(Permission p) {
        var body = new HashMap<String, Object>();
        body.put("kind", p.kind());
        body.put("itemTypeId", p.itemTypeId());
        body.put("op", p.op());
        return body;
    }

    private void setInDraft(Permission permission, boolean value) {
        webTestClient.put().uri("/api/drafts/" + draftId + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", group), "permission", body(permission), "value", value))
                .exchange().expectStatus().isNoContent();
    }

    private List<String> kinds() {
        return draftLog.entries(draftId).stream().map(Entry::kind).toList();
    }

    @Test
    void startingADraft_isItsFirstEntry_byWhoStartedIt() {
        assertThat(draftLog.entries(draftId)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("draft.started");
            assertThat(e.actorName()).isEqualTo("Root (admin)");
            assertThat(e.after()).containsEntry("name", "Draft log test");
        });
    }

    @Test
    void grantChanges_areLogged_asMade_andSetBackToLive_butNotWhenRewrittenUnchanged() {
        setInDraft(createProducts(), true);
        setInDraft(createProducts(), true);
        setInDraft(createProducts(), false);

        assertThat(kinds()).containsExactly("draft.started", "grant.granted", "grant.reset");
    }

    @Test
    void invitingAndRemovingAParticipant_areLoggedOnce() {
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        post("/api/drafts/" + draftId + "/participants", Map.of("userId", alice)).expectStatus().isNoContent();
        post("/api/drafts/" + draftId + "/participants", Map.of("userId", alice)).expectStatus().isNoContent();
        webTestClient.delete().uri("/api/drafts/" + draftId + "/participants/" + alice).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isNoContent();

        assertThat(kinds()).containsExactly("draft.started", "participant.invited", "participant.removed");
    }

    @Test
    void reconciling_isLogged() {
        UUID parent = userStore.createUserGroup("parent-" + UUID.randomUUID()).id();
        userStore.setParent(group, parent);
        setInDraft(createProducts(), true);
        userStore.setParent(group, userStore.createUserGroup("new-parent-" + UUID.randomUUID()).id());

        post("/api/drafts/" + draftId + "/reconcile", Map.of("principal", Map.of("kind", "group", "id", group),
                "permission", body(createProducts()), "resolution", "KEEP")).expectStatus().isNoContent();

        assertThat(kinds()).containsExactly("draft.started", "grant.granted", "reconcile.kept");
    }

    @Test
    void workInADraft_leavesLivesLogAlone() {
        long before = changeLog.latestPosition();

        setInDraft(createProducts(), true);

        assertThat(changeLog.entriesAfter(before)).isEmpty();
    }

    @Test
    void applying_logsItInTheDraft_andLivesEntriesSayWhichDraftTheyCameFrom() {
        setInDraft(createProducts(), true);
        long before = changeLog.latestPosition();

        post("/api/drafts/" + draftId + "/apply", null).expectStatus().isOk();

        assertThat(changeLog.entriesAfter(before)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("grant.granted");
            assertThat(e.actorName()).isEqualTo("Root (admin)");
            assertThat(e.source()).isEqualTo("draft 'Draft log test'");
        });
        assertThat(draftLog.entries(draftId).getLast()).satisfies(e -> {
            assertThat(e.kind()).isEqualTo("draft.applied");
            assertThat(e.after()).containsEntry("applied", 1);
        });
    }

    @Test
    void discarding_isLogged() {
        post("/api/drafts/" + draftId + "/discard", null).expectStatus().isOk();

        assertThat(kinds()).containsExactly("draft.started", "draft.discarded");
    }

    @Test
    void onlyAdminsReadTheLog() {
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        post("/api/drafts/" + draftId + "/participants", Map.of("userId", alice)).expectStatus().isNoContent();

        webTestClient.get().uri("/api/drafts/" + draftId + "/log").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(INVITED))
                .exchange().expectStatus().isForbidden();
        webTestClient.get().uri("/api/drafts/" + draftId + "/log").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].kind").isEqualTo("draft.started");
    }
}
