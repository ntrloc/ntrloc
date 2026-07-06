package org.ntrloc.graph.db;

import org.ntrloc.graph.acl.NtrlocPrincipal;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;

import java.util.Optional;

public interface EntityManager {

    Optional<ProjectedItem> project(SingleItemProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);

    ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);
}
