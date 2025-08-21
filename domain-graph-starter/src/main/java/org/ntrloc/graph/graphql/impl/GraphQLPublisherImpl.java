package org.ntrloc.graph.graphql.impl;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry;
import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.graphql.GraphQLTypeRegistrar;

import java.util.Set;

@DgsComponent
public class GraphQLPublisherImpl implements ReloadSchemaIndicator {

    private static final Logger LOG = LogManager.getLogger(GraphQLPublisherImpl.class);

    private boolean schemaChanged = false;

    private boolean schemaPublished = false;

    private SchemaManager schemaManager;

    private GraphQLTypeRegistrar typeRegistrar;

    public GraphQLPublisherImpl(ClusterService clusterService,
                                GraphQLTypeRegistrar registrar,
                                SchemaManager schemaManager) {
        this.typeRegistrar = registrar;
        this.schemaManager = schemaManager;
        clusterService.addClusterJoinReaction(() -> {
            LOG.info("Cluster joined; publishing GraphQL schema");
            publishSchema();
        });

        schemaManager.addSchemaChangeReaction(() -> {
            LOG.info("Schema changed; publishing GraphQL schema");
            publishSchema();
        });
    }

    private void publishSchema() {
        schemaPublished = false;
        schemaChanged = true;
    }

    @Override
    public boolean reloadSchema() {
        var needsReload = schemaChanged && !schemaPublished;
        if (needsReload) {
            LOG.info("Reloading GraphQL schema");
        }
        return needsReload;
    }

    @DgsTypeDefinitionRegistry
    public TypeDefinitionRegistry typeDefinitionRegistry() {
        Set<EntityDefinition> entityDefinitionSet = schemaManager.retrieveEntityDefinitions();
        Set<RelationshipDefinition> relationshipDefinitions = schemaManager.retrieveRelationshipDefinitions();
        try {
            return typeRegistrar.getTypeDefinitionRegistry(entityDefinitionSet, relationshipDefinitions);
        } finally {
            schemaChanged = false;
            schemaPublished = true;
        }
    }

}
