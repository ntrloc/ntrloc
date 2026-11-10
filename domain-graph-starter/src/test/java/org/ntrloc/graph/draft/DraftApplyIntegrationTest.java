package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.principal.InternalPrincipalResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Applying and discarding a draft (docs/ntrloc-drafts-plan.md step 1.7). Each test works on fresh groups
// and markers, so nothing it applies to live reaches another test.
class DraftApplyIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String INVITED = "alice@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader liveGrants;

    @Autowired
    private DraftGrantStore draftGrantStore;

    @Autowired
    private DraftAccessService draftAccessService;

    @Autowired
    private DraftService draftService;

    @Autowired
    private InternalPrincipalResolver principals;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID draftId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraftWithAliceInvited() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft apply test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
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

    private UUID newGroup() {
        return userStore.createUserGroup("draft-apply-" + UUID.randomUUID()).id();
    }

    private UUID newMarker() {
        return accessService.createMarker("draft-apply-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private Permission createProducts() {
        return Permission.itemType(fixture.productTypeId(), "create");
    }

    private static Permission readItems(UUID markerId) {
        return Permission.marker(markerId, Permission.AREA_ITEM, null, "read");
    }

    private void setInDraft(UUID groupId, Permission permission, boolean value) {
        var body = new HashMap<String, Object>();
        body.put("kind", permission.kind());
        body.put("itemTypeId", permission.itemTypeId());
        body.put("markerId", permission.markerId());
        body.put("area", permission.area());
        body.put("targetId", permission.targetId());
        body.put("op", permission.op());
        webTestClient.put().uri("/api/drafts/" + draftId + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", groupId), "permission", body, "value", value))
                .exchange().expectStatus().isNoContent();
    }

    private boolean live(UUID groupId, Permission permission) {
        return liveGrants.getPermissions("USER_GROUP", groupId).contains(permission);
    }

    private WebTestClient.ResponseSpec post(String action, String as) {
        return webTestClient.post().uri("/api/drafts/" + draftId + "/" + action).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as)).exchange();
    }

    // Each applied grant as "groupId outcome".
    @SuppressWarnings("unchecked")
    private List<String> apply() {
        Map<String, Object> body = post("apply", ADMIN).expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(((Map<String, Object>) body.get("draft")).get("status")).isEqualTo("APPLIED");
        return ((List<Map<String, Object>>) body.get("grants")).stream()
                .map(g -> ((Map<String, Object>) g.get("principal")).get("id") + " " + g.get("outcome")).toList();
    }

    @Test
    void applying_writesTheDraftsChangesToLive_andClosesTheDraft() {
        UUID group = newGroup();
        UUID marker = newMarker();
        accessService.setPermission("USER_GROUP", group, readItems(marker), true);
        setInDraft(group, createProducts(), true);
        setInDraft(group, readItems(marker), false);

        assertThat(apply()).containsExactlyInAnyOrder(group + " APPLIED", group + " APPLIED");

        assertThat(live(group, createProducts())).isTrue();
        assertThat(live(group, readItems(marker))).isFalse();
        post("apply", ADMIN).expectStatus().isNotFound();
    }

    @Test
    void afterApplying_liveHasExactlyWhatTheDraftShowed() {
        UUID group = newGroup();
        UUID marker = newMarker();
        accessService.setPermission("USER_GROUP", group, readItems(marker), true);
        setInDraft(group, createProducts(), true);
        setInDraft(group, readItems(marker), false);
        var draftRows = draftGrantStore.getGrantsView(draftId).rows();

        apply();

        assertThat(liveGrants.getGrantsView().rows()).isEqualTo(draftRows);
    }

    @Test
    void whatLiveAlreadyHas_isReportedAsAlreadyLive() {
        UUID group = newGroup();
        setInDraft(group, createProducts(), true);
        accessService.setPermission("USER_GROUP", group, createProducts(), true);

        assertThat(apply()).containsExactly(group + " ALREADY_LIVE");
        assertThat(live(group, createProducts())).isTrue();
    }

    @Test
    void grantsWhosePrincipalOrTargetIsGone_areDropped_andTheRestApply() {
        UUID deletedGroup = newGroup();
        UUID keptGroup = newGroup();
        UUID deletedMarker = newMarker();
        setInDraft(deletedGroup, createProducts(), true);
        setInDraft(keptGroup, readItems(deletedMarker), true);
        setInDraft(keptGroup, createProducts(), true);
        userStore.deleteUserGroup(deletedGroup);
        accessService.deleteMarker(deletedMarker);

        assertThat(apply()).containsExactlyInAnyOrder(deletedGroup + " DROPPED", keptGroup + " DROPPED", keptGroup + " APPLIED");
        assertThat(live(keptGroup, createProducts())).isTrue();
        assertThat(liveGrants.getPermissions("USER_GROUP", deletedGroup)).isEmpty();
    }

    @Test
    void onlyAnAdminCanApply() {
        post("apply", INVITED).expectStatus().isForbidden();
        post("apply", ADMIN).expectStatus().isOk();
    }

    @Test
    void aDiscardedDraft_cantBeAppliedOrDiscardedAgain_andItsGrantsLeaveLiveAlone() {
        UUID group = newGroup();
        setInDraft(group, createProducts(), true);

        post("discard", ADMIN).expectStatus().isOk();

        post("apply", ADMIN).expectStatus().isNotFound();
        post("discard", ADMIN).expectStatus().isNotFound();
        assertThat(live(group, createProducts())).isFalse();
    }

    @Test
    void closingADraft_dropsItsCachedGrants() {
        draftGrantStore.getGrantsView(draftId);
        assertThat(draftGrantStore.hasCachedView(draftId)).isTrue();

        post("discard", ADMIN).expectStatus().isOk();

        assertThat(draftGrantStore.hasCachedView(draftId)).isFalse();
    }

    // A write that got into the draft before it closed (its scope already made) can't land after.
    @Test
    void aWriteToADraftThatClosedMeanwhile_isRefused() {
        var admin = principals.byId(userStore.findUserByEmail(ADMIN).orElseThrow().id()).orElseThrow();
        var scope = new DraftScope(draftService.openDraft(draftId, admin), admin);

        draftService.discard(draftId, admin);

        assertThatThrownBy(() -> draftAccessService.setPermission(scope, "USER_GROUP", newGroup(), createProducts(), true))
                .isInstanceOf(DraftService.DraftNotFoundException.class);
        assertThat(draftGrantStore.overrides(draftId)).isEmpty();
    }
}
