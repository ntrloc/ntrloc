package org.ntrloc.graph.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository.MarkerRow;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.LinkProjectionSpec;
import org.ntrloc.graph.projection.ItemProjectionSpec;
import org.ntrloc.graph.projection.Operator;
import org.ntrloc.graph.projection.PropertyExistencePredicate;
import org.ntrloc.graph.projection.PropertyValuePredicate;
import org.ntrloc.graph.projection.TermsFacetFilter;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Slice C: mode-2 (field/capability-affecting) permission resolution -- property:read/write and
// link_property:read/write filtering, plus item:delete/link:delete capability flags. Every
// non-superuser fixture user is added to "everyone" (type-level read via DefaultUserGroupInitializer's
// default-open grant) and separately granted item:read/link:read on the specific instance under
// test, so each test isolates the property/capability gate specifically -- the mode-1 existence
// gate is a prerequisite, already covered by InstanceReadFilteringIntegrationTest.
//
// link:read/delete/link_property:* are all anchored to the *source* item's own marker now (markers
// only ever apply to items, never links -- see docs/ntrloc-marker-admin-ui-design-notes.md), so
// every link-related test below grants through a marker on productId (the source), not a marker on
// the link itself.
class PropertyAndCapabilityFilteringIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private DefaultUserGroupInitializer defaultUserGroupInitializer;

    @Autowired
    private SchemaManager schemaManager;

    // Not static/final: needs a real, backed security_user row now that ledger_entry.created_by
    // (Part E) has a NOT NULL FK to security_user.id -- created fresh per test in setUp() via the
    // already-autowired securityRepo, which a static initializer can't reach.
    private NtrlocPrincipal SUPERUSER;

    @BeforeEach
    void setUpSuperuser() {
        var user = securityRepo.createUser("pcf-root-" + UUID.randomUUID(), "Root", null, true);
        SUPERUSER = TestPrincipals.of(user.id(), user.externalId(), user.displayName(), null, Set.of(), true);
    }

    private UUID createProduct(String name, String color) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), name, fixture.colorPropertyId(), color), Map.of(), Set.of())), txn, SUPERUSER.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createContributor() {
        UUID id = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(id, fixture.contributorTypeId(),
                Map.of(fixture.contributorNamePropertyId(), "Ada"), Map.of(), Set.of())), txn, SUPERUSER.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return id;
    }

    private UUID createLink(UUID productId, UUID contributorId, String role) {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), role))), txn, SUPERUSER.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return linkId;
    }

    private NtrlocPrincipal newUserInEveryoneUserGroup() {
        var user = securityRepo.createUser("pcf-" + UUID.randomUUID(), "Restricted", null, false);
        UUID everyoneUserGroupId = defaultUserGroupInitializer.getDefaultUserGroupId();
        securityRepo.addUserToUserGroup(user.id(), everyoneUserGroupId);
        return TestPrincipals.of(user.id(), user.externalId(), user.externalId(), null, Set.of(everyoneUserGroupId), false);
    }

    private MarkerRow markerOnItem(UUID itemId) {
        var marker = authRepo.createMarker("pcf-" + UUID.randomUUID(), "test fixture", "ITEM_TYPE", fixture.productTypeId());
        markerAssignmentService.addItemMarker(itemId, marker.id(), SUPERUSER, "test reason");
        return marker;
    }

    private UUID grantId(UUID markerId, NtrlocPrincipal principal) {
        return authRepo.ensureMarkerGrant(markerId, "USER", principal.id());
    }

    // --- Item property read filtering ---

    // The display label is computed from every property the caller may read, not just the ones
    // requested -- but never from one they may not read.
    @Test
    void displayLabel_usesAReadablePropertyThatWasNotRequested() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        authRepo.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        var item = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, List.of("color"), false, false),
                "http://binary", principal).orElseThrow();

        assertThat(item.properties()).containsOnlyKeys("color");
        assertThat(item.displayLabel()).isEqualTo("Widget");
    }

    @Test
    void displayLabel_neverRevealsAnUnreadableProperty() {
        UUID productId = createProduct("Secret Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);
        // name -- the property the label pattern reads -- deliberately not granted

        var requestingAll = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false),
                "http://binary", principal).orElseThrow();
        var requestingColor = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, List.of("color"), false, false),
                "http://binary", principal).orElseThrow();

        assertThat(requestingAll.displayLabel()).isEqualTo(productId.toString());
        assertThat(requestingColor.displayLabel()).isEqualTo(productId.toString());
    }

    @Test
    void propertyWithNoReadGrant_isAbsentFromResponse() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        // color deliberately not granted

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().properties()).containsKey("name");
        assertThat(result.get().properties()).doesNotContainKey("color");
    }

    @Test
    void propertyGrantedViaOneOfSeveralMarkers_isPresent_unionSemantics() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        authRepo.setItemPermissions(grantId(markerOnItem(productId).id(), principal), true, false);
        authRepo.grantPropertyAccess(grantId(markerOnItem(productId).id(), principal), fixture.namePropertyId(), true, false);
        authRepo.grantPropertyAccess(grantId(markerOnItem(productId).id(), principal), fixture.colorPropertyId(), true, false);

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().properties()).containsKeys("name", "color");
    }

    @Test
    void propertyWithWriteGrantOnly_isReadable_becauseWriteImpliesRead() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), false, true);
        // Write grant but no explicit read grant for "name" -- a write grant implies read (editing a
        // value you can't see back is not a coherent capability; the admin UI already shows read as
        // "implied by write").

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().properties()).containsEntry("name", "Widget");
        assertThat(result.get().properties()).doesNotContainKey("color");
    }

    @Test
    void superuserSeesAllPropertiesRegardlessOfGrants() {
        UUID productId = createProduct("Widget", "red");

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", SUPERUSER);

        assertThat(result.get().properties()).containsKeys("name", "color");
    }

    // --- Item capability flags (edit / delete) ---

    @Test
    void editListReflectsPropertyWriteGrants() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), false, true);

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().edit()).isEqualTo(Map.of("scalars", List.of("name")));
        assertThat(result.get().permissions().delete()).isFalse();
    }

    @Test
    void deleteCapabilityReflectsItemDeleteGrant() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, true);

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().delete()).isTrue();
    }

    // Superuser gets the real, fully-enumerated tree -- same shape as anyone else, just with every
    // scalar wildcarded and every group child present, rather than a separate flag/shortcut.
    @Test
    void superuserPermissions_fullyEnumeratedEditTreeAndDeleteTrue() {
        UUID productId = createProduct("Widget", "red");

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", SUPERUSER);

        assertThat(result.get().permissions().edit()).isEqualTo(
                Map.of("scalars", List.of("*"), "groups", Map.of("dimensions", Map.of("scalars", List.of("*")))));
        assertThat(result.get().permissions().delete()).isTrue();
        assertThat(result.get().permissions().createLinks()).containsExactly("products");
    }

    // A write grant on every scalar under a nested property group must surface as that node
    // collapsing to the wildcard, not as the top-level container name (the original bug report
    // this whole edit-tree design responds to) and not leaking into sibling top-level properties.
    @Test
    void editTreeCollapsesFullyGrantedNestedObjectToWildcard() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.dimensionsWidthPropertyId(), false, true);
        authRepo.grantPropertyAccess(grantId, fixture.dimensionsHeightPropertyId(), false, true);

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().edit()).isEqualTo(
                Map.of("groups", Map.of("dimensions", Map.of("scalars", List.of("*")))));
    }

    // Partial coverage of a nested object's own children must name exactly the granted ones,
    // never collapse to the wildcard, and never include the ungranted sibling.
    @Test
    void editTreeNamesPartiallyGrantedNestedObjectScalars() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.dimensionsWidthPropertyId(), false, true);
        // Deliberately no grant on dimensions.height.

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().edit()).isEqualTo(
                Map.of("groups", Map.of("dimensions", Map.of("scalars", List.of("width")))));
    }

    // --- Link property filtering and link capability, distinguished from item-level grants ---

    // link:create is a source-item permission, not something attached to any particular existing
    // link -- so unlike the link:read/delete tests below, this doesn't need an actual link to exist
    // at all, just the source item and a can_create grant on its own marker for the perspective.
    @Test
    void createLinksCapability_governedByLinkPerspectiveCreateGrant() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), true, false, false); // link:create only

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().createLinks()).containsExactly("products");
    }

    @Test
    void createLinksCapability_absentWithoutCreateGrant() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false); // link:read only, no create

        var result = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", productId, null, true, false), "http://binary", principal);

        assertThat(result.get().permissions().createLinks()).isNull();
    }

    // --- Nested item property read filtering (through a link) ---
    //
    // filterPropertiesByReadGrant is shared between the top-level item and every linked item
    // fetchLinksByItem recurses through -- these isolate the *linked* item's own property grant,
    // with item:read/link:read/link_property:read on the path to it otherwise fully granted, so
    // the only thing varying is whether "name" (the contributor's own property) is readable.

    @Test
    void nestedItemPropertyWithNoReadGrant_isAbsentFromTheLinkedItemsProjection() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false);
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false);
        // Deliberately no property:read grant for the contributor's own "name" property.

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.item().properties()).doesNotContainKey("name");
    }

    @Test
    void nestedItemPropertyWithReadGrant_isPresentInTheLinkedItemsProjection() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false);
        UUID contributorGrantId = grantId(markerOnItem(contributorId).id(), principal);
        authRepo.setItemPermissions(contributorGrantId, true, false);
        authRepo.grantPropertyAccess(contributorGrantId, fixture.contributorNamePropertyId(), true, false);

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.item().properties()).containsEntry("name", "Ada");
    }

    @Test
    void linkPropertyWithNoReadGrant_isAbsentFromResponse() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false);
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false);
        // Deliberately no link_property:read grant for "role".

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.properties()).doesNotContainKey("role");
    }

    @Test
    void linkPropertyWithReadGrant_isPresent() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false);
        authRepo.grantLinkPropertyAccess(sourceGrantId, fixture.rolePropertyId(), true, false);
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false);

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.properties()).containsEntry("role", "author");
    }

    @Test
    void linkDeleteCapability_governedByLinkPerspectiveDeleteNotItemDelete() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, true); // item:read + item:delete on the product itself
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, true); // link:read + link:delete
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false); // target item:read only

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.permissions().delete()).isTrue();
        // The linked (contributor) item's own delete capability is separate -- no item:delete
        // grant was given on the contributor, so it must stay false even though the link is
        // deletable and the outer product is.
        assertThat(link.item().permissions().delete()).isFalse();
        // createLinks has no meaning on a link's own permissions (a link doesn't originate further
        // links) -- always null there, regardless of what create grants exist elsewhere.
        assertThat(link.permissions().createLinks()).isNull();
    }

    @Test
    void linkPropertyWriteGrant_doesNotLeakIntoLinkedItemsEditList() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        authRepo.grantLinkPerspectiveAccess(sourceGrantId, fixture.productPerspectiveId(), false, true, false);
        authRepo.grantLinkPropertyAccess(sourceGrantId, fixture.rolePropertyId(), false, true);
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false);

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        // "role" is the only property the "author" link type has, so a full grant on it
        // collapses to the wildcard, same as it would for any other fully-covered node.
        assertThat(link.permissions().edit()).isEqualTo(Map.of("scalars", List.of("*")));
        assertThat(link.item().permissions().edit()).isNull();
    }

    // --- Filter/sort property-read enforcement (requirePropertyReadableByAnyMarker) ---
    //
    // Distinct from the per-item property:read filtering above -- a collection query has no single
    // item in play yet, so this asks the coarser "could this principal ever read this property via
    // *any* marker they hold at all," independent of which item. newUserInEveryoneUserGroup's own
    // user has item-type:read (type-level, unaffected) but zero property grants of any kind, so it
    // isolates this check cleanly.

    @Test
    void collectionProjection_sortingOnAPropertyNotReadableByAnyMarker_throwsIllegalArgumentException() {
        var principal = newUserInEveryoneUserGroup();
        // The sort clause is only ever built once at least one row survives the item:read filter
        // (project() short-circuits on facetedCount == 0 before reaching it) -- so there has to be
        // a visible product here, with item:read granted but deliberately no property grant on
        // "color" at all, to actually exercise the sort-field check rather than trivially returning
        // empty before it runs.
        UUID productId = createProduct("Widget", "red");
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);

        assertThatThrownBy(() -> entityManager.project(
                new CollectionProjectionSpec("CoordinatorTestProduct", "color", "ASC"), "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("color");
    }

    @Test
    void collectionProjection_filteringOnAPropertyNotReadableByAnyMarker_throwsIllegalArgumentException() {
        var principal = newUserInEveryoneUserGroup();

        assertThatThrownBy(() -> entityManager.project(
                new CollectionProjectionSpec("CoordinatorTestProduct", null, null, new PropertyExistencePredicate("color")),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("color");
    }

    @Test
    void collectionProjection_sortingOnAPropertyGrantedViaAnyMarker_succeeds() {
        var principal = newUserInEveryoneUserGroup();
        UUID productId = createProduct("Widget", "red");
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        var result = entityManager.project(
                new CollectionProjectionSpec("CoordinatorTestProduct", "color", "ASC"), "http://binary", principal);

        assertThat(result).isNotNull();
    }

    @Test
    void collectionProjection_filteringOnAPropertyValueNotReadableByAnyMarker_throwsIllegalArgumentException() {
        var principal = newUserInEveryoneUserGroup();

        assertThatThrownBy(() -> entityManager.project(
                new CollectionProjectionSpec("CoordinatorTestProduct", null, null,
                        new PropertyValuePredicate("color", Operator.EQUALS, "red")),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("color");
    }

    // --- Explicitly-requested property read enforcement (requireExplicitlyRequestedPropertiesAreReadable) ---
    //
    // Distinct from propertyWithNoReadGrant_isAbsentFromResponse above -- that test leaves
    // properties() null ("everything"), which still silently omits an unreadable one; these name
    // the property explicitly, which has to be a loud rejection instead.

    @Test
    void itemProjection_explicitlyRequestedUnreadableProperty_throwsIllegalArgumentException() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        // color deliberately not granted, but explicitly requested below.

        assertThatThrownBy(() -> entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, null, List.of("name", "color"), true, false),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("color");
    }

    @Test
    void itemProjection_explicitlyRequestedOnlyReadableProperties_succeeds() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneUserGroup();
        UUID grantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);

        var result = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, null, List.of("name"), true, false),
                "http://binary", principal);

        assertThat(result.get().properties()).containsEntry("name", "Widget");
    }

    // --- Explicitly-requested link read enforcement (requireExplicitlyRequestedLinksAreReadable) ---
    //
    // Distinct from InstanceReadFilteringIntegrationTest's own
    // linkFiltering_defaultLinksWithoutLinkRead_isExcludedEvenWhenBothEndpointsAreVisible -- that
    // one uses the links==null default ("every direct link"), which stays silent; naming "products"
    // explicitly here has to be a loud rejection instead.

    @Test
    void itemProjection_explicitlyRequestedLinkPerspectiveWithoutLinkReadGrant_throwsIllegalArgumentException() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneUserGroup();
        UUID sourceGrantId = grantId(markerOnItem(productId).id(), principal);
        authRepo.setItemPermissions(sourceGrantId, true, false);
        // The target (contributor) also has to be independently readable, or the row is excluded
        // at the SQL level (buildLinkVisibilityPermissionFragment) before link:read is ever
        // checked -- same setup linkPropertyWithNoReadGrant_isAbsentFromResponse above uses to
        // isolate its own, different gate.
        authRepo.setItemPermissions(grantId(markerOnItem(contributorId).id(), principal), true, false);
        // Deliberately no grantLinkPerspectiveAccess call -- link:read withheld entirely, but
        // "products" is explicitly named below.

        assertThatThrownBy(() -> entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null)), true, false),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("products");
    }

    // --- Facet property-read enforcement (resolveRequestedFacets/buildTermsFacetFilterFragment) ---
    //
    // Facets resolve property names through the same path filter/sort already use, so the same
    // "any marker" check applies -- a fresh item type with its own facetable BOOLEAN property
    // (facetable without needing a controlled list, see isTermsFacetable's own comment), never
    // touching the shared fixture's own (non-facetable) properties.

    private String createFacetableItemType() {
        String typeName = "PcfFacet-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeName, "d", List.of(
                new CreatePropertyDefinitionMutation("active", "d", PropertyType.BOOLEAN, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, true)),
                null, false, null)));
        return typeName;
    }

    @Test
    void collectionProjection_facetFilterOnAPropertyNotReadableByAnyMarker_throwsIllegalArgumentException() {
        String typeName = createFacetableItemType();
        var principal = newUserInEveryoneUserGroup();
        // item-type:read comes from the default group's own auto-grant (DefaultUserGroupInitializer.
        // onItemTypeCreated); no property grant for "active" at all.

        assertThatThrownBy(() -> entityManager.project(
                new CollectionProjectionSpec(typeName, null, null, null, null, null,
                        List.of(new TermsFacetFilter("active", List.of("true"), false)), null, null, null),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    @Test
    void collectionProjection_explicitlyRequestedFacetOnAPropertyNotReadableByAnyMarker_throwsIllegalArgumentException() {
        String typeName = createFacetableItemType();
        var principal = newUserInEveryoneUserGroup();

        assertThatThrownBy(() -> entityManager.project(
                new CollectionProjectionSpec(typeName, null, null, null, null, List.of("active"), null, null, null, null),
                "http://binary", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    // facets: [] (empty, not null) auto-populates "every facetable property" -- same "give me
    // everything you can" semantics as an unnamed/default properties request, so an unreadable one
    // is silently excluded rather than rejected.
    @Test
    void collectionProjection_autoPopulatedFacetsSilentlyExcludeAnUnreadableProperty() {
        String typeName = createFacetableItemType();
        var principal = newUserInEveryoneUserGroup();

        var result = entityManager.project(
                new CollectionProjectionSpec(typeName, null, null, null, null, List.of(), null, null, null, null),
                "http://binary", principal);

        // "active" is the only facetable property on this type, so excluding it (rather than
        // rejecting the request) leaves nothing to auto-populate at all -- facets stays null, same
        // as a request that named no facets in the first place.
        assertThat(result.facets()).isNull();
    }

    @Test
    void collectionProjection_explicitlyRequestedFacetGrantedViaAnyMarker_succeeds() {
        String typeName = createFacetableItemType();
        UUID activePropertyId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals("active")).findFirst().orElseThrow().id();
        var principal = newUserInEveryoneUserGroup();
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        UUID typeId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(typeName)).findFirst().orElseThrow().id();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, typeId, Map.of(activePropertyId, true), Map.of(), Set.of())), txn, SUPERUSER.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        UUID grantId = grantId(markerOnItem(itemId).id(), principal);
        authRepo.setItemPermissions(grantId, true, false);
        authRepo.grantPropertyAccess(grantId, activePropertyId, true, false);

        var result = entityManager.project(
                new CollectionProjectionSpec(typeName, null, null, null, null, List.of("active"), null, null, null, null),
                "http://binary", principal);

        assertThat(result).isNotNull();
    }
}
