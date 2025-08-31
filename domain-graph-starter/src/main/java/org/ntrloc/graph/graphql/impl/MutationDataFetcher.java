package org.ntrloc.graph.graphql.impl;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.reactive.internal.DgsReactiveRequestData;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.graphql.GraphQLMutationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class MutationDataFetcher implements DataFetcher<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(MutationDataFetcher.class);

    private GraphQLMutationParser mutationParser;
    private EntityManager entityManager;

    public MutationDataFetcher(GraphQLMutationParser mutationParser, EntityManager entityManager) {
        this.mutationParser = mutationParser;
        this.entityManager = entityManager;
    }

    @Override
    public Object get(DataFetchingEnvironment dfe) throws Exception {
        try {
            DgsReactiveRequestData requestData = (DgsReactiveRequestData) DgsContext.getRequestData(dfe);
            URI requestUri = requestData.getServerRequest().uri();

            GraphQLFieldDefinition fd = dfe.getFieldDefinition();
            Map<String, Object> args = dfe.getArguments();
            LOG.info("Mutating entity {} with args {}", fd, args);

            var mutations = mutationParser.parseMutations(dfe.getField());
            var request = new MutationRequest(mutations);
            entityManager.executeMutation(request);

            LOG.info("OK?");

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
    }

}
