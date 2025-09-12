package org.ntrloc.graph.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.reactive.internal.DgsReactiveRequestData;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.mutation.ItemMutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationType;
import org.ntrloc.graph.graphql.mapping.SchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MutationDataFetcher implements DataFetcher<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(MutationDataFetcher.class);

    private SchemaMapper schemaMapper;
    private ItemManager itemManager;

    public MutationDataFetcher(ItemManager itemManager, SchemaMapper schemaMapper) {
        this.schemaMapper = schemaMapper;
        this.itemManager = itemManager;
    }

    @Override
    public Object get(DataFetchingEnvironment dfe) throws Exception {
        try {
            DgsReactiveRequestData requestData = (DgsReactiveRequestData) DgsContext.getRequestData(dfe);
            URI requestUri = requestData.getServerRequest().uri();

            GraphQLFieldDefinition fd = dfe.getFieldDefinition();
            Map<String, Object> args = dfe.getArguments();
            LOG.info("Mutating entity {} with args {}", fd, args);

            // TODO: this is temporary while I figure out how to best handle mutations and select-back
            var mutes = schemaMapper.parseEntityMutations(dfe.getField());

            var mutations = mutes.values().stream().flatMap(List::stream).toList();

            var request = new MutationRequest(mutations);
            var response = itemManager.executeMutation(request);

            var itemsByAction = response.getItemMutationResponses().stream().collect(Collectors.groupingBy(ItemMutationResponse::getMutationType));

            var retMap = new HashMap<String, List<ItemMutationResponse>>();
            retMap.put("created", itemsByAction.get(MutationType.CREATE));
            retMap.put("updated", itemsByAction.get(MutationType.UPDATE));
            retMap.put("deleted", itemsByAction.get(MutationType.DELETE));

            LOG.info("Executed mutation {} with response {}", fd, retMap);

            return retMap;
        } catch (Exception e) {
            LOG.error("Error while executing mutation", e);
            throw new RuntimeException("Error while executing mutation", e);
        }
    }

}
