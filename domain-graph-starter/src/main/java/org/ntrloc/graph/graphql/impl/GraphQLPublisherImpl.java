package org.ntrloc.graph.graphql.impl;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry;
import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.graphql.GraphQLSchemaGenerator;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@DgsComponent
public class GraphQLPublisherImpl implements ReloadSchemaIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLPublisherImpl.class);

    private boolean schemaChanged = false;

    private boolean schemaPublished = false;

    private SchemaManager schemaManager;

    private GraphQLSchemaGenerator schemaGenerator;

    private GraphQLSchemaMapper schemaMapper;

    public GraphQLPublisherImpl(ClusterService clusterService,
                                GraphQLSchemaMapper schemaMapper,
                                GraphQLSchemaGenerator schemaGenerator,
                                SchemaManager schemaManager) {
        this.schemaMapper = schemaMapper;
        this.schemaGenerator = schemaGenerator;
        this.schemaManager = schemaManager;
        clusterService.addClusterJoinReaction(() -> {
            LOG.info("Cluster joined; publishing GraphQL schema");
            publishSchema();
        });

        // TODO: refreshing the schema should probably be based on a message to a
        // cluster-wide ReliableTopic so all instances in the cluster will refresh their schemas
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
        Set<EntityDefinition> entityDefinitions = schemaManager.retrieveEntityDefinitions();
        Set<RelationshipDefinition> relationshipDefinitions = schemaManager.retrieveRelationshipDefinitions();

        schemaMapper.mapSchemaElements(entityDefinitions, relationshipDefinitions);

        try {
            TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

            GraphqlDefinitions graphqlDefinitions = schemaGenerator.generateTypeDefinitions(entityDefinitions, relationshipDefinitions);

            for (DirectiveDefinition def: graphqlDefinitions.getDirectiveDefinitions()) {
                registry.add(def);
            }

            for (ObjectTypeDefinition def: graphqlDefinitions.getObjectTypeDefinitions()) {
                LOG.info("Registering entity definition: {}", def.getName());
                registry.add(def);
            }

            for (InputObjectTypeDefinition def: graphqlDefinitions.getInputObjectTypeDefinitions()) {
                LOG.info("Registering entity input definition {}", def);
                registry.add(def);
            }

            for (ObjectTypeExtensionDefinition def: graphqlDefinitions.getObjectTypeExtensionDefinitions()) {
                LOG.info("Registering type extension definition {}", def);
                registry.add(def);
            }

            return registry;
        } finally {
            schemaChanged = false;
            schemaPublished = true;
        }
    }

}
