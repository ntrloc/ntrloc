package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.principal.InternalPrincipalResolver;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Reading live's items and schema inside a draft, with the user's draft grants: what a grant drafted
// for a user changes is what they see in the draft, and only there. Each test has a fresh user, so
// nothing granted reaches another test.
class DraftPreviewIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String PRODUCT = "CoordinatorTestProduct";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private InternalPrincipalResolver principals;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private NtrlocPrincipal admin;
    private String draftId;
    private UserStore.UserRow user;
    private UUID marker;
    private UUID markedItem;
    private UUID unmarkedItem;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void aDraftWithAFreshUserInvited_andTwoProducts_oneMarked() {
        discardOpenDrafts();
        admin = principals.byId(userStore.findUserByEmail(ADMIN).orElseThrow().id()).orElseThrow();
        draftId = (String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Draft preview test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id");
        user = userStore.createUser("Preview User", "draft-preview-" + UUID.randomUUID() + "@example.com", false);
        webTestClient.post().uri("/api/drafts/" + draftId + "/participants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("userId", user.id())).exchange().expectStatus().isNoContent();

        marker = accessService.createMarker("draft-preview-" + UUID.randomUUID(), "", "ITEM_TYPE", fixture.productTypeId()).id();
        markedItem = createProduct();
        unmarkedItem = createProduct();
        markerAssignmentService.addItemMarker(markedItem, marker, admin, "draft preview test");
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

    private UUID createProduct() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())),
                txn, admin.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private String live() {
        return "/api";
    }

    private String draft() {
        return "/api/drafts/" + draftId;
    }

    // Product read, and item read through the marker, for the user -- in the draft only.
    private void draftTheUserReadingMarkedProducts() {
        grantInDraft(Map.of("kind", "itemType", "itemTypeId", fixture.productTypeId(), "op", "read"));
        var itemRead = new HashMap<String, Object>();
        itemRead.put("kind", "marker");
        itemRead.put("markerId", marker);
        itemRead.put("area", "item");
        itemRead.put("targetId", null);
        itemRead.put("op", "read");
        grantInDraft(itemRead);
    }

    private void grantInDraft(Map<String, Object> permission) {
        webTestClient.put().uri(draft() + "/admin/grants").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "user", "id", user.id()), "permission", permission, "value", true))
                .exchange().expectStatus().isNoContent();
    }

    @SuppressWarnings("unchecked")
    private List<String> schemaItemTypes(String base) {
        Map<String, Object> schema = webTestClient.get().uri(base + "/schema").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user.email()))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) schema.get("items")).stream().map(i -> (String) i.get("name")).toList();
    }

    private WebTestClient.ResponseSpec products(String base) {
        return webTestClient.post().uri(base + "/projection/collection").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user.email()))
                .bodyValue(Map.of("itemTypeName", PRODUCT, "limit", 500)).exchange();
    }

    @SuppressWarnings("unchecked")
    private List<String> productIds(String base) {
        Map<String, Object> result = products(base).expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) result.get("items")).stream().map(i -> (String) i.get("itemId")).toList();
    }

    private WebTestClient.ResponseSpec item(String base, UUID itemId) {
        return webTestClient.post().uri(base + "/projection/item").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user.email()))
                .bodyValue(Map.of("itemTypeName", PRODUCT, "itemId", itemId)).exchange();
    }

    @Test
    void anUntouchedDraft_showsTheUserWhatLiveDoes() {
        assertThat(schemaItemTypes(draft())).isEqualTo(schemaItemTypes(live())).doesNotContain(PRODUCT);
        products(draft()).expectStatus().isNotFound();
        products(live()).expectStatus().isNotFound();
    }

    @Test
    void aTypeReadDraftedForTheUser_isInTheDraftsSchema_andNotLives() {
        grantInDraft(Map.of("kind", "itemType", "itemTypeId", fixture.productTypeId(), "op", "read"));

        assertThat(schemaItemTypes(draft())).contains(PRODUCT);
        assertThat(schemaItemTypes(live())).doesNotContain(PRODUCT);
    }

    @Test
    void itemReadDraftedForTheUser_showsTheMarkedItemInTheDraft_only() {
        draftTheUserReadingMarkedProducts();

        assertThat(productIds(draft())).contains(markedItem.toString()).doesNotContain(unmarkedItem.toString());
        item(draft(), markedItem).expectStatus().isOk();
        item(draft(), unmarkedItem).expectStatus().isNotFound();

        products(live()).expectStatus().isNotFound();
        item(live(), markedItem).expectStatus().isNotFound();
    }

    @Test
    void anAdmin_seesEverythingInTheDraft_asLive() {
        webTestClient.post().uri(draft() + "/projection/item").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("itemTypeName", PRODUCT, "itemId", unmarkedItem)).exchange().expectStatus().isOk();
    }

    @Test
    void someoneNotInvited_cantTellTheDraftExists() {
        draftTheUserReadingMarkedProducts();
        var outsider = userStore.createUser("Outsider", "draft-preview-" + UUID.randomUUID() + "@example.com", false);

        webTestClient.get().uri(draft() + "/schema").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(outsider.email()))
                .exchange().expectStatus().isNotFound();
        webTestClient.post().uri(draft() + "/projection/item").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(outsider.email()))
                .bodyValue(Map.of("itemTypeName", PRODUCT, "itemId", markedItem)).exchange().expectStatus().isNotFound();
    }
}
