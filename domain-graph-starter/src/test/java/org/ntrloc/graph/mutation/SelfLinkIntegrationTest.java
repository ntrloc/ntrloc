package org.ntrloc.graph.mutation;

import org.ntrloc.graph.projection.ProjectionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.ntrloc.graph.principal.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers the family-tree scenario that motivated dropping schema_entity_link_perspective's old
// UNIQUE(entity_id, link_definition_id): Alice is the mother of Bob and Charlie (an asymmetric
// self-link, two differently-named perspectives on Person), and Bob and Charlie are siblings of
// each other (a symmetric self-link, two identically-named perspectives on Person -- the one case
// where a perspective name may appear twice on a type, since both are ends of the same link). Also
// covers SchemaMutationValidation.requirePerspectiveNameAvailable: otherwise a perspective name
// appears once per item type.
class SelfLinkIntegrationTest extends AbstractIntegrationTest {

    // Not static/final: needs a real, backed security_user row now that ledger_entry.created_by
    // (Part E) has a NOT NULL FK to security_user.id -- created fresh per test in setUp() via the
    // already-autowired userStore, which a static initializer can't reach.
    private NtrlocPrincipal SOME_PRINCIPAL;

    @Autowired
    private MutationRequestProcessor processor;

    @Autowired

    private ProjectionEngine projectionEngine;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private SelfLinkTestDomainInitializer fixture;

    @Autowired
    private UserStore userStore;

    @BeforeEach
    void setUpSomePrincipal() {
        var user = userStore.createUser("Test User", "sli-test-user-" + UUID.randomUUID() + "@example.com", true);
        SOME_PRINCIPAL = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
    }

    private UUID createPerson() {
        MutationResponse response = processor.process(
                new MutationRequest(List.of(new ItemCreateMutation(null, "SelfLinkPerson", Map.of())), List.of()),
                SOME_PRINCIPAL);
        return response.items().get(0).itemId();
    }

    private List<UUID> linkedItemIds(UUID personId, String perspectiveName) {
        var projected = projectionEngine.projectOne(fixture.personTypeId(), personId, "http://binary").orElseThrow();
        return projected.links().getOrDefault(perspectiveName, List.of()).stream()
                .map(link -> link.item().itemId())
                .toList();
    }

    @Test
    void familyTree_asymmetricMotherChildrenAndSymmetricSiblingSelfLinks_resolveFromBothSides() {
        UUID alice = createPerson();
        UUID bob = createPerson();
        UUID charlie = createPerson();

        processor.process(new MutationRequest(List.of(), List.of(
                new LinkCreateMutation(
                        new LinkEndpointReference("children", new ExistingItemReference(alice)),
                        new LinkEndpointReference("mother", new ExistingItemReference(bob)),
                        Map.of()),
                new LinkCreateMutation(
                        new LinkEndpointReference("children", new ExistingItemReference(alice)),
                        new LinkEndpointReference("mother", new ExistingItemReference(charlie)),
                        Map.of()),
                new LinkCreateMutation(
                        new LinkEndpointReference("sibling", new ExistingItemReference(bob)),
                        new LinkEndpointReference("sibling", new ExistingItemReference(charlie)),
                        Map.of()))), SOME_PRINCIPAL);

        assertThat(linkedItemIds(alice, "children")).containsExactlyInAnyOrder(bob, charlie);
        assertThat(linkedItemIds(bob, "mother")).containsExactly(alice);
        assertThat(linkedItemIds(charlie, "mother")).containsExactly(alice);
        assertThat(linkedItemIds(bob, "sibling")).containsExactly(charlie);
        assertThat(linkedItemIds(charlie, "sibling")).containsExactly(bob);
    }

    @Test
    void perspectiveName_usedTwiceOnTheSameItemTypeByDifferentLinks_throws() {
        UUID otherTypeId = createOtherItemType();

        assertThatThrownBy(() -> schemaService.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(fixture.personTypeId(), "mother", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(otherTypeId, "child", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is already a link perspective");
    }

    private UUID createOtherItemType() {
        String name = "SelfLinkOther-" + UUID.randomUUID();
        schemaService.applyMutations(List.of(new CreateItemDefinitionMutation(name, "d", List.of(), null, false, null)));
        return schemaService.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow().id();
    }
}
