package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Live changes a draft is told about (docs/ntrloc-system-model-notes.md §13.6): everything changed in live
// since the draft started, one notice per element (its latest change), each acknowledged on its own, and
// never in the way of applying.
class DraftNoticesIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private DraftChangeLog draftLog;

    private UUID draftId;
    private UUID a;
    private UUID b;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void twoGroupsThenADraft() {
        discardOpenDrafts();
        a = group("a");
        b = group("b");
        draftId = UUID.fromString((String) post("/api/drafts", Map.of("name", "Notices test"), ADMIN).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void discardOpenDrafts() {
        List<Map<String, Object>> open = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        for (var draft : open) post("/api/drafts/" + draft.get("id") + "/discard", null, ADMIN).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec post(String uri, Object body, String as) {
        var request = webTestClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as));
        return body == null ? request.exchange() : request.bodyValue(body).exchange();
    }

    private UUID root() {
        return userStore.findUserGroupByName("everyone").orElseThrow().id();
    }

    private UUID group(String name) {
        return userService.createUserGroup(name + "-" + UUID.randomUUID(), root()).id();
    }

    // The draft's open notices, as returned by /changes.
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> notices() {
        Map<String, Object> body = webTestClient.get().uri("/api/drafts/" + draftId + "/changes").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (List<Map<String, Object>>) body.get("notices");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> latest(Map<String, Object> notice) {
        return (Map<String, Object>) notice.get("latest");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> after(Map<String, Object> notice) {
        return (Map<String, Object>) latest(notice).get("after");
    }

    private List<String> openElements() {
        return notices().stream().map(n -> (String) latest(n).get("elementKey")).toList();
    }

    private void acknowledge(Map<String, Object> notice) {
        post("/api/drafts/" + draftId + "/acknowledge", Map.of("elementKey", latest(notice).get("elementKey"), "seq", latest(notice).get("seq")), ADMIN)
                .expectStatus().isNoContent();
    }

    @Test
    void aNewDraft_hasNothingToHear_evenAboutWhatHappenedBeforeItStarted() {
        assertThat(notices()).isEmpty();
    }

    // The example that started it (notes §13.6): no changes in the draft yet, and a group moves in live.
    @Test
    void aGroupMovedInLive_isANotice_sayingWhereFromAndTo() {
        userService.setParent(a, b);

        assertThat(notices()).singleElement().satisfies(n -> {
            assertThat(latest(n)).containsEntry("kind", "group.moved").containsEntry("elementKey", "group:" + a);
            assertThat(after(n)).containsEntry("parentId", b.toString());
            assertThat(n).containsEntry("changes", 1);
        });
    }

    @Test
    void repeatedChanges_areOneNotice_showingTheLatest_andHowManyThereWere() {
        UUID c = group("c");
        userService.setParent(a, b);
        userService.setParent(a, c);

        assertThat(notices()).filteredOn(n -> latest(n).get("elementKey").equals("group:" + a)).singleElement().satisfies(n -> {
            assertThat(after(n)).containsEntry("parentId", c.toString());
            assertThat(n).containsEntry("changes", 2);
        });
    }

    @Test
    void eachNoticeIsAcknowledgedOnItsOwn_andALaterChangeOpensItAgain() {
        userService.setParent(a, b);
        userService.renameUserGroup(b, "renamed-" + b);
        var moved = notices().stream().filter(n -> latest(n).get("elementKey").equals("group:" + a)).findFirst().orElseThrow();

        acknowledge(moved);

        assertThat(openElements()).containsExactly("group:" + b);

        userService.setParent(a, root());
        assertThat(openElements()).containsExactlyInAnyOrder("group:" + a, "group:" + b);
        assertThat(notices()).filteredOn(n -> latest(n).get("elementKey").equals("group:" + a)).singleElement()
                .satisfies(n -> assertThat(n).containsEntry("changes", 1));
    }

    @Test
    void membershipsAndGrants_areNoticesToo() {
        UUID user = userService.createUser("Joiner", "joiner-" + UUID.randomUUID() + "@example.com", false).id();
        userService.addMember(user, a);
        accessService.setPermission("USER_GROUP", b, Permission.itemType(fixture.productTypeId(), "create"), true);

        assertThat(notices()).extracting(n -> (String) latest(n).get("kind"))
                .containsExactly("user.created", "membership.added", "grant.granted");
    }

    @Test
    void acknowledging_isRecordedInTheDraftsLog_andOnlyAdminsMayDoIt() {
        userService.setParent(a, b);
        var notice = notices().getFirst();
        UUID alice = userStore.findUserByEmail(INVITED).orElseThrow().id();
        post("/api/drafts/" + draftId + "/participants", Map.of("userId", alice), ADMIN).expectStatus().isNoContent();

        post("/api/drafts/" + draftId + "/acknowledge", Map.of("elementKey", latest(notice).get("elementKey"), "seq", latest(notice).get("seq")), INVITED)
                .expectStatus().isForbidden();
        acknowledge(notice);

        assertThat(draftLog.entries(draftId).getLast().kind()).isEqualTo("notice.acknowledged");
    }

    @Test
    void openNotices_neverStopTheDraftBeingApplied() {
        userService.setParent(a, b);

        post("/api/drafts/" + draftId + "/apply", null, ADMIN).expectStatus().isOk();
    }
}
