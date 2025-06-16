package org.nterloc.graph.graphql.impl;

import com.netflix.graphql.dgs.DgsCodeRegistry;
import com.netflix.graphql.dgs.DgsComponent;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nterloc.graph.graphql.GraphQlQueryInterpreter;
import org.nterloc.graph.db.schema.EntityDefinition;
import org.nterloc.graph.db.schema.SchemaManager;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DgsComponent
public class GraphQlQueryInterpreterImpl implements GraphQlQueryInterpreter {

    private static final Logger LOG = LogManager.getLogger(GraphQlQueryInterpreter.class);

    private SchemaManager schemaManager;

    public GraphQlQueryInterpreterImpl(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @DgsCodeRegistry
    public GraphQLCodeRegistry.Builder registry(GraphQLCodeRegistry.Builder codeRegistryBuilder, TypeDefinitionRegistry registry) {
        Set<EntityDefinition> entityDefinitionSet = schemaManager.retrieveEntityDefinitions();
        if (entityDefinitionSet.isEmpty()) {
            return codeRegistryBuilder.clearDataFetchers();
        } else {
            Map<String, DataFetcher<?>> retrievalDataFetchers = entityDefinitionSet.stream()
                    .collect(Collectors.toMap(entityDefinition -> entityDefinition.getName(), entityDefinition -> {
                        DataFetcher<Object> fetcher = (dfe) -> {
                            LOG.info("Data fetching entity {}", entityDefinition.getName());
                            return Map.of("ISBN", "whatever");
                        };
                        return fetcher;
                    }));


            DataFetcher<Object> mutatingFetcher = (dfe) -> {
                GraphQLFieldDefinition fd = dfe.getFieldDefinition();
                Map<String, Object> args = dfe.getArguments();
                LOG.info("Mutating entity {} with args {}", fd, args);

                return Map.of("ISBN", "whatever");
            };

            return codeRegistryBuilder
                    .dataFetchers("Query", retrievalDataFetchers)
                    .dataFetchers("Mutation", Map.of("addCover", mutatingFetcher));
        }

    }

}
