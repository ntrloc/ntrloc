package org.ntrloc.graph.db.partition.binary;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/binary")
public class BinaryController {

    private final BinaryPartitionManager binaryPartitionManager;

    public BinaryController(BinaryPartitionManager binaryPartitionManager) {
        this.binaryPartitionManager = binaryPartitionManager;
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
