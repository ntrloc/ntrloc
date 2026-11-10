package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-test-data", havingValue = "true")
@DependsOn("schemaService")
@DependsOnDatabaseInitialization
public class AuthorizationTestDataInitializer implements ApplicationRunner {

    private final SchemaService schemaService;
    private final UserStore userStore;
    private final AccessService accessService;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;

    public AuthorizationTestDataInitializer(SchemaService schemaService, UserStore userStore,
                                             AccessService accessService,
                                             DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.schemaService = schemaService;
        this.userStore = userStore;
        this.accessService = accessService;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    // Not @PostConstruct: applyMutations() below publishes SchemaChangeEvent, and
    // RegisterStore's @EventListener reaction to it (creating this type's register
    // table) isn't wired up until every singleton has finished construction. ApplicationRunner
    // guarantees this runs after the whole context -- including that listener -- is ready.
    @Override
    public void run(ApplicationArguments args) {
        // Test item types are created through the real mutation pipeline (not raw DDL) so
        // SchemaService's cache stays consistent — it has no external invalidation hook
        // besides applyMutations. AclTestUnmarkedDoc deliberately gets no marker assignment at
        // all, to prove admins bypass the default-deny behavior that blocks everyone else.
        schemaService.applyMutations(List.of(
                new CreateItemDefinitionMutation("AclTestPublicDoc", "ACL tracer bullet: group-granted type", List.of(), null, false, null),
                new CreateItemDefinitionMutation("AclTestConfidentialDoc", "ACL tracer bullet: user-granted type", List.of(), null, false, null),
                new CreateItemDefinitionMutation("AclTestUnmarkedDoc", "ACL tracer bullet: no marker assigned, admin-only", List.of(), null, false, null)
        ));

        Map<String, UUID> itemsByName = schemaService.getAdminSchema().items().stream()
                .collect(Collectors.toMap(item -> item.name(), item -> item.id()));
        UUID publicDocId = itemsByName.get("AclTestPublicDoc");
        UUID confidentialDocId = itemsByName.get("AclTestConfidentialDoc");
        UUID unmarkedDocId = itemsByName.get("AclTestUnmarkedDoc");

        // DefaultUserGroupInitializer.onItemTypeCreated reacts to the CreateItemDefinitionMutation
        // above by immediately granting the "everyone" group read on each of these three types
        // (nothing covers them yet at that instant) -- by design, so newly created item types
        // are readable by default until something more specific takes over. These three exist
        // specifically to prove the *opposite* (default-deny, opt-in via marker), so that default
        // grant has to be stripped for them before it's meaningful to assert anything below.
        revokeDefaultReadGrant(publicDocId);
        revokeDefaultReadGrant(confidentialDocId);
        revokeDefaultReadGrant(unmarkedDocId);

        var alice = userStore.createUser("Alice (viewer group member)", "alice@example.com", false);
        var bob = userStore.createUser("Bob (viewer group member)", "bob@example.com", false);
        var carol = userStore.createUser("Carol (direct grant, no group)", "carol@example.com", false);
        var root = userStore.createUser("Root (admin)", "root@example.com", true);
        defaultUserGroupInitializer.addUserToDefaultUserGroup(alice.id());
        defaultUserGroupInitializer.addUserToDefaultUserGroup(bob.id());
        defaultUserGroupInitializer.addUserToDefaultUserGroup(carol.id());
        defaultUserGroupInitializer.addUserToDefaultUserGroup(root.id());

        var viewers = userStore.createUserGroup("viewers");
        userStore.addUserToUserGroup(alice.id(), viewers.id());
        userStore.addUserToUserGroup(bob.id(), viewers.id());

        accessService.grantItemType(publicDocId, "USER_GROUP", viewers.id(), GrantStoreReader.ITEM_TYPE_READ);
        accessService.grantItemType(confidentialDocId, "USER", carol.id(), GrantStoreReader.ITEM_TYPE_READ);
    }

    private void revokeDefaultReadGrant(UUID itemTypeId) {
        UUID everyoneUserGroupId = userStore.findUserGroupByName(DefaultUserGroupInitializer.DEFAULT_GROUP_NAME).orElseThrow().id();
        accessService.revokeItemType(itemTypeId, "USER_GROUP", everyoneUserGroupId, GrantStoreReader.ITEM_TYPE_READ);
    }
}
