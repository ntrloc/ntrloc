package org.ntrloc.graph.graphql;

import com.netflix.graphql.dgs.DgsCodeRegistry;
import com.netflix.graphql.dgs.DgsComponent;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DgsComponent
public class GraphQLDataFetcherRegistrar {

    private static final Logger LOG = LogManager.getLogger(GraphQLDataFetcherRegistrar.class);

    private SchemaManager schemaManager;
    private MutationDataFetcher mutationDataFetcher;
    private QueryDataFetcher queryDataFetcher;

    public GraphQLDataFetcherRegistrar(SchemaManager schemaManager, MutationDataFetcher mutationDataFetcher, QueryDataFetcher queryDataFetcher) {
        this.schemaManager = schemaManager;
        this.mutationDataFetcher = mutationDataFetcher;
        this.queryDataFetcher = queryDataFetcher;
    }

    @DgsCodeRegistry
    public GraphQLCodeRegistry.Builder registry(GraphQLCodeRegistry.Builder codeRegistryBuilder, TypeDefinitionRegistry registry) {

        Set<ItemDefinition> itemDefinitionSet = schemaManager.retrieveItemDefinitions();
        if (itemDefinitionSet.isEmpty()) {
            return codeRegistryBuilder.clearDataFetchers();
        } else {

            Map<String, DataFetcher<?>> retrievalDataFetchers = itemDefinitionSet.stream().collect(Collectors.toMap(ItemDefinition::getName, item -> queryDataFetcher));

            codeRegistryBuilder = codeRegistryBuilder.dataFetchers("Query", retrievalDataFetchers);
            codeRegistryBuilder = codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates("Mutation", "execute"), mutationDataFetcher);

            return codeRegistryBuilder;
        }

    }

}
