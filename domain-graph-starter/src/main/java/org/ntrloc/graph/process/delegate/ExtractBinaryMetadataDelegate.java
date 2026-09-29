package org.ntrloc.graph.process.delegate;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.process.ProcessAccessible;
import org.ntrloc.mediaprocessor.grpc.ExtractMetadata;
import org.ntrloc.mediaprocessor.grpc.ImageChunk;
import org.ntrloc.mediaprocessor.grpc.ImageMetadata;
import org.ntrloc.mediaprocessor.grpc.ImageProcessorGrpc;
import org.ntrloc.mediaprocessor.grpc.Operation;
import org.ntrloc.mediaprocessor.grpc.Output;
import org.ntrloc.mediaprocessor.grpc.Pipeline;
import org.ntrloc.mediaprocessor.grpc.PipelineStep;
import org.ntrloc.mediaprocessor.grpc.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Calls the media processor for one binary's metadata and writes the result back -- started by
// BinaryMetadataExtractionTrigger, run by Flowable's own async job executor (this service task is
// flowable:async="true" in extract-binary-metadata.bpmn20.xml), never a request thread. See
// docs/ntrloc-dynamic-properties-design-notes.md sections 8/9 for this pass's scope.
@Component("extractBinaryMetadataDelegate")
@ProcessAccessible
public class ExtractBinaryMetadataDelegate implements JavaDelegate {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractBinaryMetadataDelegate.class);
    private static final int CHUNK_SIZE = 65536;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final String OUTPUT_NAME = "metadata";

    private final BinaryPartitionManager binaryPartitionManager;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ImageProcessorGrpc.ImageProcessorStub> imageProcessorStub;

    public ExtractBinaryMetadataDelegate(BinaryPartitionManager binaryPartitionManager, ObjectMapper objectMapper,
                                          ObjectProvider<ImageProcessorGrpc.ImageProcessorStub> imageProcessorStub) {
        this.binaryPartitionManager = binaryPartitionManager;
        this.objectMapper = objectMapper;
        this.imageProcessorStub = imageProcessorStub;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID binaryContentId = UUID.fromString(
                (String) execution.getVariable(BinaryMetadataExtractionTrigger.BINARY_CONTENT_ID_VARIABLE));

        var property = binaryPartitionManager.getBinaryProperty(binaryContentId).orElse(null);
        if (property == null || property.mimeType() == null || !property.mimeType().startsWith("image/")) {
            // Not an artificial first-pass restriction -- VideoProcessorGrpc has no EXIF/IPTC-
            // equivalent operation at all today, so image is genuinely everything the media
            // processor can extract embedded metadata from right now.
            LOG.debug("Binary {} is not an image (mimeType={}) -- nothing to extract", binaryContentId,
                    property == null ? null : property.mimeType());
            return;
        }

        ImageProcessorGrpc.ImageProcessorStub stub = imageProcessorStub.getIfAvailable();
        if (stub == null) {
            LOG.debug("No media processor configured -- skipping metadata extraction for {}", binaryContentId);
            return;
        }

        String responseJson;
        try {
            responseJson = extractMetadata(stub, binaryContentId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read binary " + binaryContentId + " for metadata extraction", e);
        }
        if (responseJson == null) {
            LOG.debug("Media processor returned no metadata result for {}", binaryContentId);
            return;
        }

        Map<String, Object> response = parseResponse(responseJson);
        binaryPartitionManager.updateMetadata(binaryContentId, asMap(response.get("extracted")), asMap(response.get("derived")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String responseJson) {
        return objectMapper.readValue(responseJson, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    // Streams the pipeline spec (one ExtractMetadata step -- the media processor returns two sibling
    // objects, {"extracted": {...(almost) everything it can tell about the file...}, "derived":
    // {...facts it computed, e.g. GPS coordinates combined into decimal degrees...}}; an
    // admin-configurable exclusion list on the processor itself trims "extracted", "derived" is
    // exempt from it. execute() above splits this response into those two maps and hands them to
    // BinaryPartitionManager.updateMetadata, which stores them under binary_content's own "metadata"/
    // "calculated" keys respectively -- see ExtractMetadata's own proto
    // comment and ImageOperations.extractMetadata's javadoc) as the first chunk, then the binary's
    // own bytes, matching the chunked-request shape
    // ImageProcessor.ProcessImage expects. Blocking on the response here is fine -- this only ever
    // runs on Flowable's own async job executor thread, never a request one. A genuine gRPC failure
    // or timeout is allowed to propagate (via IllegalStateException below) rather than being caught
    // -- Flowable's own async job retry handles it; no bespoke retry logic for this pass.
    private String extractMetadata(ImageProcessorGrpc.ImageProcessorStub stub, UUID binaryContentId) throws IOException {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        StreamObserver<ImageChunk> requestObserver = stub.processImage(new StreamObserver<>() {
            private String json;

            @Override
            public void onNext(Result result) {
                if (result.getPayloadCase() == Result.PayloadCase.DATA && OUTPUT_NAME.equals(result.getName())) {
                    json = result.getData().getData().toStringUtf8();
                }
            }

            @Override
            public void onError(Throwable t) {
                resultFuture.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                resultFuture.complete(json);
            }
        });

        Pipeline pipeline = Pipeline.newBuilder()
                .addSteps(PipelineStep.newBuilder()
                        .setOperation(Operation.newBuilder().setExtractMetadata(ExtractMetadata.newBuilder()))
                        .setOutput(Output.newBuilder().setName(OUTPUT_NAME)))
                .build();
        requestObserver.onNext(ImageChunk.newBuilder()
                .setMetadata(ImageMetadata.newBuilder().addPipelines(pipeline))
                .build());

        var binary = binaryPartitionManager.retrieve(binaryContentId)
                .orElseThrow(() -> new IllegalStateException("Binary " + binaryContentId + " has no content to read"));
        try (InputStream in = binary.stream()) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                requestObserver.onNext(ImageChunk.newBuilder()
                        .setData(ByteString.copyFrom(buffer, 0, read))
                        .build());
            }
        }
        requestObserver.onCompleted();

        try {
            return resultFuture.get(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for media processor response for " + binaryContentId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Media processor call failed for " + binaryContentId, e);
        }
    }

}
