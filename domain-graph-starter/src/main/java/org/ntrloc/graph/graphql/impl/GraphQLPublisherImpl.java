package org.ntrloc.graph.graphql.impl;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry;
import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.graphql.GraphQLSchemaGenerator;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;

import java.util.Set;

@DgsComponent
public class GraphQLPublisherImpl implements ReloadSchemaIndicator {

    private static final Logger LOG = LogManager.getLogger(GraphQLPublisherImpl.class);

    private boolean schemaChanged = false;

    private boolean schemaPublished = false;

    private SchemaManager schemaManager;

    private GraphQLSchemaGenerator schemaGenerator;

    public GraphQLPublisherImpl(ClusterService clusterService,
                                GraphQLSchemaGenerator generator,
                                SchemaManager schemaManager) {
        this.schemaGenerator = generator;
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
        return schemaChanged && !schemaPublished;
    }

    @DgsTypeDefinitionRegistry
    public TypeDefinitionRegistry typeDefinitionRegistry() {
        Set<EntityDefinition> entityDefinitionSet = schemaManager.retrieveEntityDefinitions();
        Set<RelationshipDefinition> relationshipDefinitions = schemaManager.retrieveRelationshipDefinitions();
        try {
            return schemaGenerator.generateTypeDefinitions(entityDefinitionSet, relationshipDefinitions);
        } finally {
            schemaChanged = false;
            schemaPublished = true;
        }
    }

}
