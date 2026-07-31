package org.ntrloc.graph.db.partition.authorization;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.schema.event.SchemaChangeEvent;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@DependsOn({"securityInitializer", "authorizationInitializer"})
public class DefaultGroupInitializer {

    public static final String DEFAULT_GROUP_NAME = "everyone";

    private final SecurityRepository securityRepo;
    private final AuthorizationRepository authorizationRepo;
    private final JdbcClient jdbcClient;

    public DefaultGroupInitializer(SecurityRepository securityRepo, AuthorizationRepository authorizationRepo,
                                    JdbcClient jdbcClient) {
        this.securityRepo = securityRepo;
        this.authorizationRepo = authorizationRepo;
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void ensureGroupExists() {
        securityRepo.findGroupByName(DEFAULT_GROUP_NAME)
                .orElseGet(() -> securityRepo.createGroup(DEFAULT_GROUP_NAME));
    }

    @EventListener(ApplicationReadyEvent.class)
    void populateAfterStartup() {
        var group = securityRepo.findGroupByName(DEFAULT_GROUP_NAME).orElseThrow();
        addAllExistingUsersToGroup(group.id());
        grantReadForUncoveredItemTypes(group.id());
    }

    @EventListener
    public void onItemTypeCreated(SchemaChangeEvent.ItemTypeCreated event) {
        var group = securityRepo.findGroupByName(DEFAULT_GROUP_NAME).orElseThrow();
        grantReadForItemType(event.itemTypeId(), group.id());
    }

    @EventListener
    public void onLinkTypeCreated(SchemaChangeEvent.LinkTypeCreated event) {
        // Link types may need read markers in the future; hook is here for when that's built.
    }

    public UUID getDefaultGroupId() {
        return securityRepo.findGroupByName(DEFAULT_GROUP_NAME).orElseThrow().id();
    }

    private void addAllExistingUsersToGroup(UUID groupId) {
        jdbcClient.sql("""
                INSERT INTO security_group_member (user_id, group_id)
                SELECT id, :groupId FROM security_user
                WHERE id NOT IN (SELECT user_id FROM security_group_member WHERE group_id = :groupId)
                """)
                .param("groupId", groupId)
                .update();
    }

    private void grantReadForUncoveredItemTypes(UUID groupId) {
        var coveredItemTypes = authorizationRepo.getMarkerIdsByItemType().keySet();
        jdbcClient.sql("SELECT id, name FROM schema_item")
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name")})
                .list()
                .stream()
                .filter(row -> !coveredItemTypes.contains((UUID) row[0]))
                .forEach(row -> grantReadForItemType((UUID) row[0], groupId));
    }

    private void grantReadForItemType(UUID itemTypeId, UUID groupId) {
        String markerName = "default-read-" + itemTypeId.toString();
        var existing = jdbcClient.sql("SELECT id FROM authorization_marker WHERE name = :name")
                .param("name", markerName)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .optional();
        UUID markerId;
        if (existing.isPresent()) {
            markerId = existing.get();
        } else {
            markerId = authorizationRepo.createMarker(markerName, "Default read access (auto-created)").id();
        }
        jdbcClient.sql("""
                INSERT INTO authorization_item_type_marker (item_type_id, marker_id)
                VALUES (:itemTypeId, :markerId) ON CONFLICT DO NOTHING
                """)
                .param("itemTypeId", itemTypeId).param("markerId", markerId).update();
        jdbcClient.sql("""
                INSERT INTO authorization_grant (id, marker_id, principal_type, principal_id, operation)
                VALUES (gen_random_uuid(), :markerId, 'GROUP', :groupId, :operation) ON CONFLICT DO NOTHING
                """)
                .param("markerId", markerId).param("groupId", groupId)
                .param("operation", PermissionService.ITEM_READ).update();
    }
}
