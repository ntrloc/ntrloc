package org.ntrloc.graph.db.partition.binary;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    // Spring MVC has no multipart handling that doesn't fully parse the request (via
    // request.getParts()) before this method runs -- spring.servlet.multipart.enabled=false
    // (application.yml) turns that off entirely, so this gets the raw, still-unconsumed
    // HttpServletRequest instead. JakartaServletFileUpload's *streaming* API
    // (getItemIterator, not parseRequest) reads bytes directly off the request body as they
    // arrive, one FileItemInput at a time -- the servlet-world equivalent of what
    // Flux<PartEvent> gave under WebFlux, and the reason this library is here at all rather
    // than just MultipartFile. The returned iterator is single-pass: each item's stream must be
    // consumed (or at least advanced past) before the next item becomes available, since both
    // read directly off the one underlying request stream.
    @PostMapping("/upload")
    ResponseEntity<UploadResponse> upload(HttpServletRequest request) throws IOException, FileUploadException {
        if (!JakartaServletFileUpload.isMultipartContent(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a multipart/form-data request");
        }
        // Raw type deliberately, matching Apache Commons' own documented usage: the generic
        // FileItem/FileItemFactory parameters only matter to the non-streaming parseRequest() path
        // (ignored entirely here); FileItemInput/FileItemInputIterator (the streaming types this
        // method actually uses) aren't parameterized by them at all.
        @SuppressWarnings({"rawtypes", "unchecked"})
        JakartaServletFileUpload upload = new JakartaServletFileUpload();
        FileItemInputIterator items = upload.getItemIterator(request);
        while (items.hasNext()) {
            FileItemInput item = items.next();
            if (item.isFormField()) {
                continue;
            }
            UUID id = binaryPartitionManager.store(item.getInputStream());
            return ResponseEntity.created(URI.create("/api/binary/" + id))
                    .body(new UploadResponse(id));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file part found in the request");
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
