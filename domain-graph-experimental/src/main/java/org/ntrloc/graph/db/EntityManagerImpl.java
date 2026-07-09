package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Service
public class EntityManagerImpl implements EntityManager {

    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;
    private final PermissionService permissionService;

    public EntityManagerImpl(RegisterPartitionManager registerPartitionManager, SchemaManager schemaManager, PermissionService permissionService) {
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
        this.permissionService = permissionService;
    }

    @Override
    public Optional<ProjectedItem> project(SingleItemProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal) {
        UUID itemTypeId = resolveItemTypeId(spec.itemTypeName());
        requireReadAccess(principal, itemTypeId, spec.itemTypeName());
        return registerPartitionManager.projectOne(itemTypeId, spec.itemId(), binaryBaseUrl);
    }

    @Override
    public ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal) {
        UUID itemTypeId = resolveItemTypeId(spec.itemTypeName());
        requireReadAccess(principal, itemTypeId, spec.itemTypeName());
        return registerPartitionManager.project(itemTypeId, spec, binaryBaseUrl);
    }

    private void requireReadAccess(NtrlocPrincipal principal, UUID itemTypeId, String itemTypeName) {
        if (!permissionService.canReadItemType(principal, itemTypeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item type: " + itemTypeName);
        }
    }

    private UUID resolveItemTypeId(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .map(item -> item.id())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown item type: " + itemTypeName));
    }
}
