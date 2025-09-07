package org.ntrloc.graph.graphql.impl;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.reactive.internal.DgsReactiveRequestData;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponseItem;
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

            var itemsByAction = response.getItems().stream().collect(Collectors.groupingBy(MutationResponseItem::getMutationType));

            var retMap = new HashMap<String, List<MutationResponseItem>>();
            retMap.put("created", itemsByAction.get(MutationResponseItem.MutationType.CREATE));
            retMap.put("updated", itemsByAction.get(MutationResponseItem.MutationType.UPDATE));
            retMap.put("deleted", itemsByAction.get(MutationResponseItem.MutationType.DELETE));

            return retMap;
        } catch (Exception e) {
            LOG.error("Error while executing mutation", e);
            throw new RuntimeException("Error while executing mutation", e);
        }
    }

}
