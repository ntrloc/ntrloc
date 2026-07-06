package org.ntrloc.graph.acl.repository;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.acl.PermissionService;
import org.ntrloc.graph.schema.SchemaManager;
import org.ntrloc.graph.schema.definition.mutation.CreateItemDefinitionMutation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "ntrloc.acl", name = "seed-test-data", havingValue = "true")
@DependsOn({"schemaManager", "aclInitializer"})
public class AclTestDataInitializer {

    private final SchemaManager schemaManager;
    private final AclRepository aclRepo;

    public AclTestDataInitializer(SchemaManager schemaManager, AclRepository aclRepo) {
        this.schemaManager = schemaManager;
        this.aclRepo = aclRepo;
    }

    @PostConstruct
    void init() {
        // Test item types are created through the real mutation pipeline (not raw DDL) so
        // SchemaManager's cache stays consistent — it has no external invalidation hook
        // besides applyMutations.
        schemaManager.applyMutations(List.of(
                new CreateItemDefinitionMutation("AclTestPublicDoc", "ACL tracer bullet: group-granted type", List.of()),
                new CreateItemDefinitionMutation("AclTestConfidentialDoc", "ACL tracer bullet: user-granted type", List.of())
        ));

        Map<String, UUID> itemsByName = schemaManager.getAdminSchema().items().stream()
                .collect(Collectors.toMap(item -> item.name(), item -> item.id()));
        UUID publicDocId = itemsByName.get("AclTestPublicDoc");
        UUID confidentialDocId = itemsByName.get("AclTestConfidentialDoc");

        var alice = aclRepo.createUser("alice", "Alice (viewer group member)");
        var bob = aclRepo.createUser("bob", "Bob (viewer group member)");
        var carol = aclRepo.createUser("carol", "Carol (direct grant, no group)");

        var viewers = aclRepo.createGroup("viewers");
        aclRepo.addUserToGroup(alice.id(), viewers.id());
        aclRepo.addUserToGroup(bob.id(), viewers.id());

        var publicRead = aclRepo.createMarker("public-read", "Grants read of AclTestPublicDoc");
        var confidentialRead = aclRepo.createMarker("confidential-read", "Grants read of AclTestConfidentialDoc");
        aclRepo.assignMarkerToItemType(publicDocId, publicRead.id());
        aclRepo.assignMarkerToItemType(confidentialDocId, confidentialRead.id());

        aclRepo.grant(publicRead.id(), "GROUP", viewers.id(), PermissionService.ITEM_READ);
        aclRepo.grant(confidentialRead.id(), "USER", carol.id(), PermissionService.ITEM_READ);
    }
}
