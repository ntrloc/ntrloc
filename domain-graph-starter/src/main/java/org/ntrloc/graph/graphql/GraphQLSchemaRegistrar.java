package org.ntrloc.graph.graphql;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry;
import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.graphql.mapping.SchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@DgsComponent
public class GraphQLSchemaRegistrar implements ReloadSchemaIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLSchemaRegistrar.class);

    private boolean schemaChanged = false;

    private boolean schemaPublished = false;

    private SchemaManager schemaManager;

    private SchemaMapper newSchemaMapper;

    public GraphQLSchemaRegistrar(ClusterService clusterService,
                                  SchemaManager schemaManager,
                                  SchemaMapper newSchemaMapper) {
        this.schemaManager = schemaManager;
        this.newSchemaMapper = newSchemaMapper;
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
        Set<ItemDefinition> itemDefinitions = schemaManager.retrieveItemDefinitions();
        Set<LinkDefinition> linkDefinitions = schemaManager.retrieveLinkDefinitions();

        newSchemaMapper.mapSchema(itemDefinitions, linkDefinitions);

        try {
            TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

            for (ObjectTypeDefinition def: newSchemaMapper.getOutputTypes()) {
                LOG.info("Registering entity definition: {}", def.getName());
                registry.add(def);
            }

            for (InputObjectTypeDefinition def: newSchemaMapper.getInputTypes()) {
                LOG.info("Registering entity input definition {}", def);
                registry.add(def);
            }

            for (ObjectTypeExtensionDefinition def: newSchemaMapper.getExtensionTypes()) {
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
