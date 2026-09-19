package org.ntrloc.graph.db.partition.binary;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePartEvent;
import org.springframework.http.codec.multipart.PartEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/binary")
public class BinaryController {

    public record UploadResponse(UUID id) {}

    private final BinaryPartitionManager binaryPartitionManager;

    public BinaryController(BinaryPartitionManager binaryPartitionManager) {
        this.binaryPartitionManager = binaryPartitionManager;
    }

    // Flux<PartEvent> streams each part's body as it arrives off the wire, backed by Spring's own
    // token-based multipart parser rather than the Part/FilePart machinery (getMultipartData()),
    // which does buffer/spool a whole part before handing it back. Verified empirically, not just
    // from the docs: a slow consumer downstream (BinaryPartitionManagerImpl.store) with a fast local
    // network kept backend RSS flat for a 300MB upload, and a 12GB upload (bigger than the 9GB test
    // heap) completed without OOM once store() consumed the Flux directly instead of bridging it to
    // a blocking InputStream.
    @PostMapping("/upload")
    Mono<ResponseEntity<UploadResponse>> upload(@RequestBody Flux<PartEvent> partEvents) {
        Flux<DataBuffer> content = partEvents
                .ofType(FilePartEvent.class)
                .map(PartEvent::content);

        return binaryPartitionManager.store(content)
                .map(id -> ResponseEntity.created(URI.create("/api/binary/" + id))
                        .body(new UploadResponse(id)));
    }

    @GetMapping("/{id}")
    ResponseEntity<?> retrieve(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {

        var result = binaryPartitionManager.retrieve(id);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var stream = result.get();
        var info = stream.info();
        String etag = "\"" + info.sha256() + "\"";

        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        info.mimeType() != null ? info.mimeType() : "application/octet-stream"))
                .contentLength(info.length())
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable())
                .body(new InputStreamResource(stream.stream()));
    }
}
