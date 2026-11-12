package org.ntrloc.graph.draft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.CountedPredicateTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.schema.SchemaService;
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

// Every kind of permission, through the grants API, live and in a draft: set, read back, cleared, and
// applied. Each test works on a fresh group and marker.
class GrantsInEveryAreaIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private AccessService accessService;

    @Autowired
    private GrantStoreReader liveGrants;

    @Autowired
    private DraftGrantStore draftGrantStore;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private CoordinatorTestDomainInitializer products;

    @Autowired
    private CountedPredicateTestDomainInitializer portfolios;

    private UUID draftId;
    private UUID group;
    private List<Permission> everyKind;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startADraft() {
        discardOpenDrafts();
        draftId = UUID.fromString((String) webTestClient.post().uri("/api/drafts").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("name", "Every area test")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id"));
        group = userGroupStore.createUserGroup("every-area-" + UUID.randomUUID()).id();
        UUID marker = accessService.createMarker("every-area-" + UUID.randomUUID(), "", "ITEM_TYPE", products.productTypeId()).id();
        everyKind = List.of(
                Permission.itemType(products.productTypeId(), "read"),
                Permission.itemType(products.productTypeId(), "create"),
                Permission.forMarker(marker, Permission.AREA_ITEM, null, "read"),
                Permission.forMarker(marker, Permission.AREA_ITEM, null, "delete"),
                Permission.forMarker(marker, Permission.AREA_PROPERTY, products.namePropertyId(), "read"),
                Permission.forMarker(marker, Permission.AREA_PROPERTY, products.namePropertyId(), "write"),
                Permission.forMarker(marker, Permission.AREA_LINK_PROPERTY, products.rolePropertyId(), "read"),
                Permission.forMarker(marker, Permission.AREA_LINK_PROPERTY, products.rolePropertyId(), "write"),
                Permission.forMarker(marker, Permission.AREA_PERSPECTIVE, products.productPerspectiveId(), "create"),
                Permission.forMarker(marker, Permission.AREA_PERSPECTIVE, products.productPerspectiveId(), "read"),
                Permission.forMarker(marker, Permission.AREA_PERSPECTIVE, products.productPerspectiveId(), "delete"),
                Permission.forMarker(marker, Permission.AREA_TRANSITION, publishTransitionId(), null),
                Permission.forMarker(marker, Permission.AREA_START, portfolios.publicationMachineId(), null));
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

    private UUID publishTransitionId() {
        return schemaService.getAdminSchema().items().stream()
                .flatMap(item -> item.stateMachines() == null ? java.util.stream.Stream.empty() : item.stateMachines().stream())
                .filter(machine -> machine.id().equals(portfolios.publicationMachineId()))
                .flatMap(machine -> machine.states().stream())
                .filter(state -> state.id().equals(portfolios.draftStateId()))
                .flatMap(state -> state.transitions().stream())
                .findFirst().orElseThrow().id();
    }

    private String live() {
        return "/api/admin/grants";
    }

    private String draft() {
        return "/api/drafts/" + draftId + "/admin/grants";
    }

    private void set(String address, Permission permission, boolean value) {
        var body = new HashMap<String, Object>();
        body.put("kind", permission.kind());
        body.put("itemTypeId", permission.itemTypeId());
        body.put("markerId", permission.markerId());
        body.put("area", permission.area());
        body.put("targetId", permission.targetId());
        body.put("op", permission.op());
        webTestClient.put().uri(address).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .bodyValue(Map.of("principal", Map.of("kind", "group", "id", group), "permission", body, "value", value))
                .exchange().expectStatus().isNoContent();
    }

    @SuppressWarnings("unchecked")
    private List<Permission> permissionsAt(String address) {
        List<Map<String, Object>> body = (List<Map<String, Object>>) (List<?>) webTestClient.get().uri(address + "?principal=group:" + group)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) body.getFirst().get("permissions")).stream()
                .map(p -> new Permission((String) p.get("kind"), uuid(p.get("itemTypeId")), uuid(p.get("markerId")),
                        (String) p.get("area"), uuid(p.get("targetId")), (String) p.get("op")))
                .toList();
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString((String) value);
    }

    @Test
    void everyKindOfPermission_canBeSetAndClearedLive() {
        everyKind.forEach(p -> set(live(), p, true));
        assertThat(permissionsAt(live())).containsExactlyInAnyOrderElementsOf(everyKind);

        everyKind.forEach(p -> set(live(), p, false));
        assertThat(permissionsAt(live())).isEmpty();
    }

    @Test
    void everyKindOfPermission_setInADraft_isInTheDraftOnly_untilApplied() {
        everyKind.forEach(p -> set(draft(), p, true));

        assertThat(permissionsAt(draft())).containsExactlyInAnyOrderElementsOf(everyKind);
        assertThat(permissionsAt(live())).isEmpty();

        webTestClient.post().uri("/api/drafts/" + draftId + "/apply").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk();
        assertThat(liveGrants.getPermissions("USER_GROUP", group)).containsExactlyInAnyOrderElementsOf(everyKind);
    }

    @Test
    void everyKindOfPermission_clearedInADraft_isGoneFromTheDraftOnly() {
        everyKind.forEach(p -> set(live(), p, true));

        everyKind.forEach(p -> set(draft(), p, false));

        assertThat(permissionsAt(draft())).isEmpty();
        assertThat(permissionsAt(live())).containsExactlyInAnyOrderElementsOf(everyKind);
        assertThat(draftGrantStore.overrides(draftId)).hasSize(everyKind.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTargetsListIncludesStateMachinesAndTheirTransitions() {
        webTestClient.get().uri(draft() + "/targets").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.itemTypes[?(@.id == '" + portfolios.portfolioTypeId() + "')].stateMachines[0].transitions[0].id")
                .value(ids -> assertThat((List<Object>) ids).containsExactly(publishTransitionId().toString()));
    }
}
