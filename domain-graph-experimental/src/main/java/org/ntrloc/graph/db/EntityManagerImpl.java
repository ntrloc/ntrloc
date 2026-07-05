package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.ntrloc.graph.schema.SchemaManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Service
public class EntityManagerImpl implements EntityManager {

    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;

    public EntityManagerImpl(RegisterPartitionManager registerPartitionManager, SchemaManager schemaManager) {
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
    }

    @Override
    public Optional<ProjectedItem> project(SingleItemProjectionSpec spec, String binaryBaseUrl) {
        UUID itemTypeId = resolveItemTypeId(spec.itemTypeName());
        return registerPartitionManager.projectOne(itemTypeId, spec.itemId(), binaryBaseUrl);
    }

    @Override
    public ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl) {
        UUID itemTypeId = resolveItemTypeId(spec.itemTypeName());
        return registerPartitionManager.project(itemTypeId, spec, binaryBaseUrl);
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
