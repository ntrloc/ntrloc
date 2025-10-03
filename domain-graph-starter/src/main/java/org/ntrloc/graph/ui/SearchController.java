package org.ntrloc.graph.ui;

import com.netflix.graphql.dgs.reactive.DgsReactiveQueryExecutor;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(SearchController.SEARCH_PREFIX)
public class SearchController {

    static final String SEARCH_PREFIX = "/search";

    private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

    private String resourceLocation;
    private TemplateEngine templateEngine;
    private ResourceLoader resourceLoader;
    private SchemaManager schemaManager;

    @Autowired
    private DgsReactiveQueryExecutor queryExecutor;

    private SearchController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                             @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                             ResourceLoader resourceLoader,
                             SchemaManager schemaManager) {
        this.resourceLocation = resourceLocation;
        this.templateEngine = templateEngine;
        this.resourceLoader = resourceLoader;
        this.schemaManager = schemaManager;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> index(ServerWebExchange exchange) {
        var requestPath = exchange.getRequest().getPath().toString();
        var relPath = requestPath.substring(SEARCH_PREFIX.length());
        var params = exchange.getRequest().getQueryParams();
        if (relPath.isEmpty() || relPath.substring(1).isEmpty()) {
            Context context = new Context();
            context.setVariable("schemaManager", schemaManager);
            var result = templateEngine.process("/search/search.html", context);
            return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
        }

        try {
            if (relPath.endsWith(".html")) {
                Context context = new Context();
                context.setVariable("schemaManager", schemaManager);
                var result = templateEngine.process(relPath, context);
                return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
            } else {
                return ResponseEntity.ok(resourceLoader.getResource(resourceLocation + relPath));
            }
        } catch (Exception er) {
            LOG.warn("Error resolving web resource", er);
            return ResponseEntity.ok(resourceLoader.getResource(resourceLocation + relPath));
        }

    }

    @GetMapping("/execute")
    public ResponseEntity<Resource> execute(ServerWebExchange exchange, @RequestParam Map<String, String> arguments) {
        var serverRequest = ServerRequest.create(exchange, List.of());

        var query = arguments.get("searchObject");

        queryExecutor.execute(query, Map.of(), Map.of(), exchange.getRequest().getHeaders(), null, serverRequest).subscribe(result -> {
            LOG.info("Result: {}", result);
        });

        var requestPath = exchange.getRequest().getPath();
        String path = requestPath.toString();
        if (path.substring(1).isEmpty()) {
            var headers = new HttpHeaders();
            headers.add("Location", "/search.html");
            return new ResponseEntity<>(headers, HttpStatus.MOVED_TEMPORARILY);
        } else {
            return new ResponseEntity<>(new ByteArrayResource("".getBytes()), HttpStatus.OK);
        }
    }

}
