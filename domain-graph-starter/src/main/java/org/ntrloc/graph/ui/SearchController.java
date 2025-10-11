package org.ntrloc.graph.ui;

import com.netflix.graphql.dgs.reactive.DgsReactiveQueryExecutor;
import graphql.ExecutionResult;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.TemplateEngine;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(SearchController.SEARCH_PREFIX)
public class SearchController extends AbstractController {

    static final String SEARCH_PREFIX = "/search";

    private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

    @Autowired
    private DgsReactiveQueryExecutor queryExecutor;

    SearchController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                             @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                             ResourceLoader resourceLoader,
                             SchemaManager schemaManager) {
        super("search", resourceLocation, templateEngine, resourceLoader, schemaManager);
    }

    @GetMapping("/execute")
    public Mono<ResponseEntity<Resource>> execute(ServerWebExchange exchange, @RequestParam Map<String, String> arguments) {
        var serverRequest = ServerRequest.create(exchange, List.of());

        var query = arguments.get("searchObject");

        Mono<ExecutionResult> executionResult = queryExecutor.execute(query, Map.of(), Map.of(), exchange.getRequest().getHeaders(), null, serverRequest);

        return executionResult.map(result -> {
            LOG.info("Got query result {}", result);
            var data = (Map<String, List>)result.getData();
            var items = data.values().stream().flatMap(List::stream).toList();
            return processTemplate(String.format("/%s/index.html", requestPrefix), Map.of("searchResults", items));
        });

    }

}
