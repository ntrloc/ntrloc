package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.ntrloc.graph.db.partition.security.UserService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

// @DependsOnDatabaseInitialization (not a literal @DependsOn on Flyway's own bean name) is Boot's
// purpose-built mechanism for this: FlywayAutoConfiguration already @Imports
// DatabaseInitializationDependencyConfigurer, which wires a real BeanDefinition-level dependsOn
// from any bean carrying this annotation onto whatever DatabaseInitializerDetector finds (Flyway's
// migration bean) -- guaranteed ordering, not an assumption about JdbcClient bean wiring. Needed
// because ensureUserGroupExists() below is the only DML left running at boot now that schema_* tables
// are Flyway-managed rather than always-already-there via the old *Initializer @PostConstruct DDL.
@Component
@DependsOnDatabaseInitialization
public class DefaultUserGroupInitializer {

    public static final String DEFAULT_GROUP_NAME = "everyone";

    private final UserStore userStore;
    private final UserService userService;
    private final AccessService accessService;
    private final JdbcClient jdbcClient;

    public DefaultUserGroupInitializer(UserStore userStore, UserService userService, AccessService accessService,
                                    JdbcClient jdbcClient) {
        this.userStore = userStore;
        this.userService = userService;
        this.accessService = accessService;
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void ensureUserGroupExists() {
        if (userStore.findUserGroupByName(DEFAULT_GROUP_NAME).isEmpty()) {
            ChangeLog.withSource("startup", () -> userService.createRootUserGroup(DEFAULT_GROUP_NAME));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void populateAfterStartup() {
        var group = userStore.findUserGroupByName(DEFAULT_GROUP_NAME).orElseThrow();
        ChangeLog.withSource("startup", () -> {
            userService.addEveryUser(group.id());
            grantReadForUncoveredItemTypes(group.id());
        });
    }

    @EventListener
    public void onItemTypeCreated(SchemaChangeEvent.ItemTypeCreated event) {
        var group = userStore.findUserGroupByName(DEFAULT_GROUP_NAME).orElseThrow();
        grantReadForItemType(event.itemTypeId(), group.id());
    }

    @EventListener
    public void onLinkTypeCreated(SchemaChangeEvent.LinkTypeCreated event) {
        // Link types have no independent type-visibility grant of their own -- visible iff both
        // participant item types are visible (see docs/ntrloc-security-projections-summary.md
        // "Type Visibility"). Nothing to do here; hook stays for parity with onItemTypeCreated.
    }

    public UUID getDefaultUserGroupId() {
        return userStore.findUserGroupByName(DEFAULT_GROUP_NAME).orElseThrow().id();
    }

    // Every user-creation path (UserAdminController, LocalAccountSeeder,
    // AuthorizationTestDataInitializer) must call this so nobody ends up uncovered until the next
    // restart's populateAfterStartup backfill catches them -- addUserToUserGroup is itself idempotent
    // (ON CONFLICT DO NOTHING) so calling this twice for the same user is harmless. A caller that
    // constructor-injects DefaultUserGroupInitializer is guaranteed ensureUserGroupExists() has already run
    // (Spring fully initializes a bean, @PostConstruct included, before handing it to a dependent
    // bean's constructor), so getDefaultUserGroupId() below never races the group's own creation.
    public void addUserToDefaultUserGroup(UUID userId) {
        userService.addMember(userId, getDefaultUserGroupId());
    }

    // "Uncovered" means no default-visibility decision has ever been made for this type -- not
    // "has no grant right now". An admin's explicit revocation must survive this backfill running
    // again on a later restart; see the migration comment on schema_item.default_visibility_decided.
    private void grantReadForUncoveredItemTypes(UUID groupId) {
        jdbcClient.sql("SELECT id FROM schema_item WHERE NOT default_visibility_decided")
                .query((rs, _) -> rs.getObject("id", UUID.class))
                .list()
                .forEach(itemTypeId -> grantReadForItemType(itemTypeId, groupId));
    }

    private void grantReadForItemType(UUID itemTypeId, UUID groupId) {
        accessService.grantItemType(itemTypeId, "USER_GROUP", groupId, GrantStoreReader.ITEM_TYPE_READ);
        jdbcClient.sql("UPDATE schema_item SET default_visibility_decided = TRUE WHERE id = :itemTypeId")
                .param("itemTypeId", itemTypeId).update();
    }
}
