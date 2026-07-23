package org.ntrloc.graph.db;

import org.ntrloc.graph.db.mutation.MutationRequest;
import org.ntrloc.graph.db.mutation.MutationRequestProcessor;
import org.ntrloc.graph.db.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.process.ProcessAccessible;
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

// @ProcessAccessible: reachable from process scripts/delegate expressions as ${entityManager}
// (ProcessAccessibleBeansMap, ProcessEngineConfig) -- the *only* bean opted in for this, now that
// it fronts both reads and writes. RegisterPartitionManager/LedgerPartitionManagerImpl were
// briefly annotated directly before this class grew mutate(); now that EntityManager is the one
// seam for the whole entity system, exposing the partition managers themselves alongside it would
// just be two doors to the same room -- removed from both (see their own history for why they had
// it in the first place).
@Service("entityManager")
@ProcessAccessible
public class EntityManagerImpl implements EntityManager {

    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;
    private final PermissionService permissionService;
    private final MutationRequestProcessor mutationRequestProcessor;

    public EntityManagerImpl(RegisterPartitionManager registerPartitionManager, SchemaManager schemaManager,
                              PermissionService permissionService, MutationRequestProcessor mutationRequestProcessor) {
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
        this.permissionService = permissionService;
        this.mutationRequestProcessor = mutationRequestProcessor;
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

    // Pure passthrough -- no permission check, matching MutationRequestProcessor's own documented
    // gap (its class comment: "Permission checks are a separate, not-yet-built component").
    // Deliberately not bundled into this change; see project()'s own requireReadAccess for the
    // write-side equivalent this will eventually need.
    // principal here is @Nullable and purely for ledger attribution (who made this change), not a
    // permission gate the way project()'s principal is -- see MutationRequestProcessor.process's
    // own note.
    @Override
    public MutationResponse mutate(MutationRequest request, NtrlocPrincipal principal) {
        return mutationRequestProcessor.process(request, principal);
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
