package org.ntrloc.graph.db.partition.binary;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.mutation.ItemCreateMutation;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.register.RegisterProjectionTestDomainInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers BinaryPartitionManagerImpl.updateMetadata -- the diff/replace write (design-notes section
// 10's "trigger 3") -- separately from ExtractBinaryMetadataDelegate, which is now just a thin
// caller of it.
class BinaryPartitionManagerImplUpdateMetadataIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOK_TYPE = "RegisterProjectionTestBook";

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID storeBinary() {
        byte[] content = ("update-metadata test " + UUID.randomUUID()).getBytes();
        var buffer = new DefaultDataBufferFactory().wrap(content);
        return binaryPartitionManager.store(Flux.just(buffer)).block();
    }

    @Test
    void updateMetadata_firstExtraction_setsExtractedAndDerivedWithoutTouchingTheWriteOnceColumns() {
        UUID id = storeBinary();
        var before = binaryPartitionManager.getBinaryProperty(id).orElseThrow();

        binaryPartitionManager.updateMetadata(id,
                Map.of("colorspace", "sRGB"),
                Map.of("location", Map.of("latitude", 1.0, "longitude", 2.0)));

        var after = binaryPartitionManager.getBinaryProperty(id).orElseThrow();
        assertThat(after.length()).isEqualTo(before.length());
        assertThat(after.sha256()).isEqualTo(before.sha256());
        assertThat(after.metadata()).containsKey("hash");
        @SuppressWarnings("unchecked")
        var extracted = (Map<String, Object>) after.metadata().get("metadata");
        assertThat(extracted).containsEntry("colorspace", "sRGB");
    }

    @Test
    void updateMetadata_calledAgain_replacesRatherThanMergesTheExtractedSubtree() {
        UUID id = storeBinary();
        binaryPartitionManager.updateMetadata(id, Map.of("colorspaceOld", "sRGB"), Map.of());

        binaryPartitionManager.updateMetadata(id, Map.of("colorspaceNew", "CMYK"), Map.of());

        var property = binaryPartitionManager.getBinaryProperty(id).orElseThrow();
        @SuppressWarnings("unchecked")
        var extracted = (Map<String, Object>) property.metadata().get("metadata");
        // A shallow jsonb merge (the old delegate's own SQL) would have left both keys present --
        // this is exactly the behavior that made re-extraction unable to actually drop fields.
        assertThat(extracted).containsOnlyKeys("colorspaceNew");
    }

    @Test
    void updateMetadata_forAnUnknownBinaryId_isANoOp() {
        // No exception is the assertion -- the binary_content row simply doesn't exist.
        binaryPartitionManager.updateMetadata(UUID.randomUUID(), Map.of("x", "y"), Map.of());
    }

    @Test
    void updateMetadata_reconcilesTheRegistryForEveryPropertyCurrentlyReferencingTheBinary() {
        UUID binaryId = storeBinary();
        UUID propertyId = fixture.attachmentPropertyId();
        String addedKey = "colorspace_" + UUID.randomUUID();
        String addedPath = "metadata." + addedKey;
        int before = referenceCount(propertyId, addedPath).orElse(0);

        // Two items sharing the same binary under the same property -- the reconciliation must scale
        // by the actual number of current references (2), not always by one.
        createBookWithAttachment(binaryId);
        createBookWithAttachment(binaryId);

        binaryPartitionManager.updateMetadata(binaryId, Map.of(addedKey, "value"), Map.of());

        assertThat(referenceCount(propertyId, addedPath)).contains(before + 2);
    }

    private UUID createBookWithAttachment(UUID binaryId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("title", "Dune");
        properties.put("attachment", binaryId.toString());
        MutationResponse response = webTestClient.mutate().defaultHeader("X-Ntrloc-User", "root").build()
                .post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemCreateMutation(null, BOOK_TYPE, properties)), List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();
        return response.items().get(0).itemId();
    }

    private Optional<Integer> referenceCount(UUID propertyId, String path) {
        return jdbcClient.sql("""
                SELECT bpdp.reference_count FROM binary_property_dynamic_property bpdp
                JOIN binary_dynamic_property bdp ON bdp.id = bpdp.dynamic_property_id
                WHERE bpdp.property_id = :propertyId AND bdp.path = :path
                """)
                .param("propertyId", propertyId)
                .param("path", path)
                .query(Integer.class)
                .optional();
    }
}
