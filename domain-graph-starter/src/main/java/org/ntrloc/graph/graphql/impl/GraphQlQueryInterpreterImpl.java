package org.ntrloc.graph.graphql.impl;

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
import org.ntrloc.graph.graphql.GraphQlQueryInterpreter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DgsComponent
public class GraphQlQueryInterpreterImpl implements GraphQlQueryInterpreter {

    private static final Logger LOG = LogManager.getLogger(GraphQlQueryInterpreter.class);

    private SchemaManager schemaManager;
    private MutationDataFetcher mutationDataFetcher;

    public GraphQlQueryInterpreterImpl(SchemaManager schemaManager, MutationDataFetcher mutationDataFetcher) {
        this.schemaManager = schemaManager;
        this.mutationDataFetcher = mutationDataFetcher;
    }

    @DgsCodeRegistry
    public GraphQLCodeRegistry.Builder registry(GraphQLCodeRegistry.Builder codeRegistryBuilder, TypeDefinitionRegistry registry) {

        Set<ItemDefinition> itemDefinitionSet = schemaManager.retrieveEntityDefinitions();
        if (itemDefinitionSet.isEmpty()) {
            return codeRegistryBuilder.clearDataFetchers();
        } else {
            Map<String, DataFetcher<?>> retrievalDataFetchers = itemDefinitionSet.stream()
                    .collect(Collectors.toMap(entityDefinition -> entityDefinition.getName(), entityDefinition -> {
                        DataFetcher<Object> fetcher = (dfe) -> {
                            LOG.info("Data fetching entity {}", entityDefinition.getName());
                            return List.of(Map.of("properties", Map.of("name", "YO MAMA!")));
                        };
                        return fetcher;
                    }));

            /*
            DataFetcher<Object> mutatingFetcher = (dfe) -> {
                try {
                    DgsReactiveRequestData requestData = (DgsReactiveRequestData) DgsContext.getRequestData(dfe);
                    URI requestUri = requestData.getServerRequest().uri();

                    GraphQLFieldDefinition fd = dfe.getFieldDefinition();
                    Map<String, Object> args = dfe.getArguments();
                    LOG.info("Mutating entity {} with args {}", fd, args);

                    Field execField = dfe.getField();
                    execField.getSelectionSet().getSelections().forEach(selection -> {
                        Field childField = (Field) selection;
                        List<Argument> arguments = childField.getArguments(); // this is the list of inputs
                        for (Argument argument : arguments) {
                            ArrayValue inputs = (ArrayValue) argument.getValue();
                            for (Value value : inputs.getValues()) {
                                LOG.info("Mutating with {}", value);
                            }
                        }
                    });

                    Map<String, Object> fakePhoto = Map.of("properties", Map.of("name", "YO MAMA!"));
                    List<Map<String, Object>> fakePhotos = List.of(fakePhoto);

                    Map<String, Object> fakePhotographer = Map.of("id", "testID", "properties", Map.of("name", "Bill Nye"));
                    List<Map<String, Object>> fakePhotographers = List.of(fakePhotographer);

                    Map<String, Object> result = Map.of("Photo", fakePhotos, "Photographer", fakePhotographers);
                    return result;
                } catch (Exception e) {
                    LOG.error("Error while executing mutation", e);
                    throw new RuntimeException("Error while executing mutation", e);
                }
            };

             */

            codeRegistryBuilder = codeRegistryBuilder.dataFetchers("Query", retrievalDataFetchers);
            codeRegistryBuilder = codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates("Mutation", "execute"), mutationDataFetcher);

            return codeRegistryBuilder;

        }

    }

}
