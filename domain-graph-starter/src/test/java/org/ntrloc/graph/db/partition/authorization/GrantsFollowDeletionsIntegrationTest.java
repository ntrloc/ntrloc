package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.AddPropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeletePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Live stays consistent on its own (docs/ntrloc-system-model-notes.md §13.2): when a user, a group or a
// grant target goes, so do its grants -- in the tables and in the grant view permission checks read.
class GrantsFollowDeletionsIntegrationTest extends AbstractIntegrationTest {

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
    private GrantStoreReader grants;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private CoordinatorTestDomainInitializer products;

    @Autowired
    private JdbcClient jdbcClient;

    private Permission createProducts() {
        return Permission.itemType(products.productTypeId(), "create");
    }

    private Permission readItems(UUID markerId) {
        return Permission.forMarker(markerId, Permission.AREA_ITEM, null, "read");
    }

    private UUID newMarker() {
        return accessService.createMarker("deletions-" + UUID.randomUUID(), "", "ITEM_TYPE", products.productTypeId()).id();
    }

    // Grant rows for the principal, in either grant table.
    private long grantRows(UUID principalId) {
        return jdbcClient.sql("""
                SELECT (SELECT COUNT(*) FROM authorization_item_type_grant WHERE principal_id = :id)
                     + (SELECT COUNT(*) FROM marker_grant WHERE principal_id = :id)
                """).param("id", principalId).query(Long.class).single();
    }

    private WebTestClient.ResponseSpec deleteGroup(UUID groupId, boolean force) {
        return webTestClient.delete().uri("/api/admin/user-groups/" + groupId + (force ? "?force=true" : ""))
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN)).exchange();
    }

    @Test
    void deletingAGroup_deletesItsGrants() {
        UUID group = userGroupStore.createUserGroup("deleted-" + UUID.randomUUID()).id();
        accessService.setPermission("USER_GROUP", group, createProducts(), true);
        accessService.setPermission("USER_GROUP", group, readItems(newMarker()), true);

        deleteGroup(group, false).expectStatus().isNoContent();

        assertThat(grantRows(group)).isZero();
        assertThat(grants.getPermissions("USER_GROUP", group)).isEmpty();
    }

    @Test
    void deletingAGroupWithSubgroups_isRefused_andKeepsEverything() {
        UUID parent = userGroupStore.createUserGroup("parent-" + UUID.randomUUID()).id();
        UUID child = userGroupStore.createUserGroup("child-" + UUID.randomUUID(), parent).id();
        accessService.setPermission("USER_GROUP", child, createProducts(), true);

        deleteGroup(parent, false).expectStatus().isEqualTo(409);

        assertThat(userGroupStore.findUserGroupById(parent)).isPresent();
        assertThat(grants.getPermissions("USER_GROUP", child)).containsExactly(createProducts());
    }

    @Test
    void forcingIt_deletesTheSubgroups_andAllTheirGrants() {
        UUID parent = userGroupStore.createUserGroup("parent-" + UUID.randomUUID()).id();
        UUID child = userGroupStore.createUserGroup("child-" + UUID.randomUUID(), parent).id();
        UUID grandchild = userGroupStore.createUserGroup("grandchild-" + UUID.randomUUID(), child).id();
        accessService.setPermission("USER_GROUP", parent, createProducts(), true);
        accessService.setPermission("USER_GROUP", grandchild, readItems(newMarker()), true);

        deleteGroup(parent, true).expectStatus().isNoContent();

        assertThat(userGroupStore.findUserGroupById(child)).isEmpty();
        assertThat(userGroupStore.findUserGroupById(grandchild)).isEmpty();
        assertThat(grantRows(parent) + grantRows(grandchild)).isZero();
        assertThat(grants.getPermissions("USER_GROUP", grandchild)).isEmpty();
    }

    @Test
    void deletingAUser_deletesTheirGrants() {
        UUID user = userStore.createUser("Deleted", "deleted-" + UUID.randomUUID() + "@example.com", false).id();
        accessService.setPermission("USER", user, createProducts(), true);

        webTestClient.delete().uri("/api/admin/users/" + user).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isNoContent();

        assertThat(grantRows(user)).isZero();
        assertThat(grants.getPermissions("USER", user)).isEmpty();
    }

    @Test
    void deletingAPropertyThatHasGrants_takesThemOutOfTheGrantView() {
        String name = "deletable" + UUID.randomUUID().toString().substring(0, 8);
        schemaService.applyMutations(List.of(new AddPropertyDefinitionMutation(PropertyContainerKind.ITEM, products.productTypeId(), name,
                "deleted by this test", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, false)));
        UUID property = schemaService.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(products.productTypeId()))
                .flatMap(item -> item.properties().stream())
                .filter(p -> p.name().equals(name))
                .findFirst().orElseThrow().id();
        UUID group = userGroupStore.createUserGroup("property-grants-" + UUID.randomUUID()).id();
        Permission readProperty = Permission.forMarker(newMarker(), Permission.AREA_PROPERTY, property, "read");
        accessService.setPermission("USER_GROUP", group, readProperty, true);
        assertThat(grants.getPermissions("USER_GROUP", group)).contains(readProperty);

        schemaService.applyMutations(List.of(new DeletePropertyDefinitionMutation(property)));

        assertThat(grants.getPermissions("USER_GROUP", group)).doesNotContain(readProperty);
    }
}
