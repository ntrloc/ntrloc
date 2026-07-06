package org.ntrloc.graph.db.partition.authorization.repository;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "ntrloc.security", name = "seed-test-data", havingValue = "true")
@DependsOn({"schemaManager", "securityInitializer", "authorizationInitializer"})
public class AuthorizationTestDataInitializer {

    private final SchemaManager schemaManager;
    private final SecurityRepository securityRepo;
    private final AuthorizationRepository authorizationRepo;

    public AuthorizationTestDataInitializer(SchemaManager schemaManager, SecurityRepository securityRepo, AuthorizationRepository authorizationRepo) {
        this.schemaManager = schemaManager;
        this.securityRepo = securityRepo;
        this.authorizationRepo = authorizationRepo;
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

        var alice = securityRepo.createUser("alice", "Alice (viewer group member)");
        var bob = securityRepo.createUser("bob", "Bob (viewer group member)");
        var carol = securityRepo.createUser("carol", "Carol (direct grant, no group)");

        var viewers = securityRepo.createGroup("viewers");
        securityRepo.addUserToGroup(alice.id(), viewers.id());
        securityRepo.addUserToGroup(bob.id(), viewers.id());

        var publicRead = authorizationRepo.createMarker("public-read", "Grants read of AclTestPublicDoc");
        var confidentialRead = authorizationRepo.createMarker("confidential-read", "Grants read of AclTestConfidentialDoc");
        authorizationRepo.assignMarkerToItemType(publicDocId, publicRead.id());
        authorizationRepo.assignMarkerToItemType(confidentialDocId, confidentialRead.id());

        authorizationRepo.grant(publicRead.id(), "GROUP", viewers.id(), PermissionService.ITEM_READ);
        authorizationRepo.grant(confidentialRead.id(), "USER", carol.id(), PermissionService.ITEM_READ);
    }
}
