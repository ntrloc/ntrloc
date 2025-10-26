package org.ntrloc.graph.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.impl.MultipartParser;
import org.ntrloc.graph.db.storage.BinaryContentInfoWithStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/binary")
public class BinaryController {

    private static final Logger LOG = LogManager.getLogger(BinaryController.class);

    private Pattern boundaryPattern = Pattern.compile("multipart/form-data; boundary=(.+)");

    private ItemManager itemManager;

    public BinaryController(ItemManager itemManager) {
        this.itemManager = itemManager;
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
            MultipartParser multipartParser = new MultipartParser(boundary, itemManager);
            return multipartParser.parse(bufferFlux);
        } else {
            return Mono.empty();
        }
    }

    @GetMapping("/{uuid}")
    ResponseEntity<InputStreamResource> downloadBinaries(@PathVariable String uuid) {
        try {
            Optional<BinaryContentInfoWithStream> streamOpt = itemManager.getBinaryStream(uuid);
            if (streamOpt.isPresent()) {
                return ResponseEntity.ok(new InputStreamResource(streamOpt.get().getBinaryStream()));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException ioe) {
            return ResponseEntity.internalServerError().build();
        }
    }

}
