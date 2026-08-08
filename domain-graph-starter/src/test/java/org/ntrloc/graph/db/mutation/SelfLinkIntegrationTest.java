package org.ntrloc.graph.db.mutation;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
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
// each other (a symmetric self-link, two identically-named perspectives on Person). Also covers
// SchemaMutationValidation.requireConsistentPerspectiveTarget, the guardrail that replaced the
// dropped constraint: a perspective name must resolve to the same target across every link
// definition that uses it for a given entity.
class SelfLinkIntegrationTest extends AbstractIntegrationTest {

    private static final ResolvedPrincipal SOME_PRINCIPAL =
            new ResolvedPrincipal(UUID.randomUUID(), "test-user", "Test User", null, Set.of(), true);

    @Autowired
    private MutationRequestProcessor processor;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SelfLinkTestDomainInitializer fixture;

    private UUID createPerson() {
        MutationResponse response = processor.process(
                new MutationRequest(List.of(new ItemCreateMutation(null, "SelfLinkPerson", Map.of())), List.of()),
                SOME_PRINCIPAL);
        return response.items().get(0).itemId();
    }

    private List<UUID> linkedItemIds(UUID personId, String perspectiveName) {
        var projected = registerPartitionManager.projectOne(fixture.personTypeId(), personId, "http://binary").orElseThrow();
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
    void perspectiveName_reusedForADifferentTargetOnTheSameEntity_throws() {
        UUID otherTypeId = createOtherItemType();

        assertThatThrownBy(() -> schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(fixture.personTypeId(), "mother", "d", 0, 1),
                new CreatePerspectiveDefinitionMutation(otherTypeId, "child", "d", 0, null))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already targets a different type");
    }

    private UUID createOtherItemType() {
        String name = "SelfLinkOther-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(name, "d", List.of(), null, false, null)));
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow().id();
    }
}
