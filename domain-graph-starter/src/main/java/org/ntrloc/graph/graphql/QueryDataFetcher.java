package org.ntrloc.graph.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.reactive.internal.DgsReactiveRequestData;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.graphql.mapping.SchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class QueryDataFetcher implements DataFetcher<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(QueryDataFetcher.class);

    private SchemaMapper schemaMapper;
    private ItemManager itemManager;

    public QueryDataFetcher(ItemManager itemManager, SchemaMapper schemaMapper) {
        this.schemaMapper = schemaMapper;
        this.itemManager = itemManager;
    }

    @Override
    public Object get(DataFetchingEnvironment dfe) throws Exception {

        DgsReactiveRequestData requestData = (DgsReactiveRequestData) DgsContext.getRequestData(dfe);
        URI requestUri = requestData.getServerRequest().uri();
        var downloadEndpoint = requestUri.resolve("/binary/download");

        GraphQLFieldDefinition fd = dfe.getFieldDefinition();
        Map<String, Object> args = dfe.getArguments();
        LOG.debug("Executing query {} with args {}", fd, args);

        var field = dfe.getField();
        var projectionSpec = schemaMapper.parseProjectionSpec(field);

        List<ItemProjection> projections = itemManager.executeProjection(projectionSpec, downloadEndpoint);
        List<ItemProjection> translatedProjections = projections.stream().map(schemaMapper::translateItemProjection).toList();
        LOG.debug("Executed query {} with projections {}", projectionSpec, translatedProjections);

        return translatedProjections;
    }

}
