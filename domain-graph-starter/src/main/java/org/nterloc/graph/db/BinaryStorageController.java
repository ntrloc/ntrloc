package org.nterloc.graph.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nterloc.graph.db.impl.MultipartParser;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/binary")
public class BinaryStorageController {

    private static final Logger LOG = LogManager.getLogger(BinaryStorageController.class);

    private Pattern boundaryPattern = Pattern.compile("multipart/form-data; boundary=(.+)");

    private EntityManager entityManager;

    public BinaryStorageController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @PutMapping("/upload")
    Mono<Map<String, String>> uploadBinaries(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String contentType = request.getHeaders().getFirst("Content-Type");
        LOG.info("Uploading content type {}", contentType);

        Flux<DataBuffer> bufferFlux = request.getBody();

        Matcher boundaryMatcher = boundaryPattern.matcher(contentType);
        if (boundaryMatcher.matches()) {
            String boundary = boundaryMatcher.group(1);
            MultipartParser multipartParser = new MultipartParser(boundary, entityManager);
            return multipartParser.parse(bufferFlux);
        } else {
            return Mono.empty();
        }
    }

}
