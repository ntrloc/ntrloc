package org.ntrloc.graph.db.partition.register;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.mutation.ItemCreateMutation;
import org.ntrloc.graph.mutation.ItemDeleteMutation;
import org.ntrloc.graph.mutation.ItemUpdateMutation;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.springframework.beans.factory.annotation.Autowired;
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

// End to end: a real item create/update/delete through the mutation/ledger/coordinator stack
// actually reconciles the binary dynamic-property registry (RegisterPartitionManager's own
// reconcileBinaryDynamicProperties, driven by BinaryDynamicPropertyRegistrar) -- as opposed to
// BinaryDynamicPropertyRegistrarIntegrationTest, which drives the registrar directly and doesn't
// prove RegisterPartitionManager actually calls it at the right moments.
//
// Uses "hash.sha256" as the path under test -- every stored binary always has one (written by
// BinaryPartitionManagerImpl.metadataJson at upload time, real extraction isn't configured in this
// test suite), so it's a real, predictable path without needing a media processor.
class RegisterPartitionManagerBinaryDynamicPropertyIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOK_TYPE = "RegisterProjectionTestBook";
    private static final String PATH = "hash.sha256";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void authenticateAsStandInUser() {
        webTestClient = webTestClient.mutate().defaultHeader("X-Ntrloc-User", "root").build();
    }

    private UUID storeBinary() {
        byte[] content = ("dynamic property test " + UUID.randomUUID()).getBytes();
        var buffer = new DefaultDataBufferFactory().wrap(content);
        return binaryPartitionManager.store(Flux.just(buffer)).block();
    }

    private UUID createBookWithAttachment(UUID binaryId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("title", "Dune");
        if (binaryId != null) properties.put("attachment", binaryId.toString());
        MutationResponse response = webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemCreateMutation(null, BOOK_TYPE, properties)), List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MutationResponse.class)
                .returnResult().getResponseBody();
        return response.items().get(0).itemId();
    }

    private void updateAttachment(UUID itemId, UUID binaryIdOrNull) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("attachment", binaryIdOrNull == null ? null : binaryIdOrNull.toString());
        webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemUpdateMutation(itemId, properties)), List.of()))
                .exchange()
                .expectStatus().isOk();
    }

    private void deleteItem(UUID itemId) {
        webTestClient.post().uri("/api/mutation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MutationRequest(List.of(new ItemDeleteMutation(itemId)), List.of()))
                .exchange()
                .expectStatus().isOk();
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

    @Test
    void create_withAnAttachment_incrementsItsDynamicPropertiesByOne() {
        // Delta, not an absolute value: this path is global across the whole property, and other
        // test classes' own createBookWithAttachment calls (e.g. the projection test suite) already
        // contribute to the same (attachmentPropertyId, "hash.sha256") counter.
        int before = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);
        UUID binaryId = storeBinary();

        createBookWithAttachment(binaryId);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH)).contains(before + 1);
    }

    @Test
    void create_withNoAttachment_registersNothing() {
        int before = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);

        createBookWithAttachment(null);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0)).isEqualTo(before);
    }

    @Test
    void update_repointingToADifferentBinary_leavesTheSharedPathAloneSinceBothBinariesHaveIt() {
        // Both binaries have "hash.sha256" (every stored binary does) -- repointing doesn't change
        // *this* path's count, since the new binary contributes it too. Proven properly by the
        // clear/delete tests below, which use a binary with no successor to keep the count up.
        UUID first = storeBinary();
        UUID itemId = createBookWithAttachment(first);
        int afterCreate = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);

        UUID second = storeBinary();
        updateAttachment(itemId, second);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH)).contains(afterCreate);
    }

    @Test
    void update_clearingTheAttachment_decrementsItsPaths() {
        UUID binaryId = storeBinary();
        UUID itemId = createBookWithAttachment(binaryId);
        int afterCreate = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);

        updateAttachment(itemId, null);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0)).isEqualTo(afterCreate - 1);
    }

    @Test
    void delete_anItemWithAnAttachment_decrementsItsPaths() {
        UUID binaryId = storeBinary();
        UUID itemId = createBookWithAttachment(binaryId);
        int afterCreate = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);

        deleteItem(itemId);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0)).isEqualTo(afterCreate - 1);
    }

    // Mirrors design-notes section 10's worked example directly: two items sharing the exact same
    // binary under the same property, one leaving (deleted) must not zero out what the other still
    // justifies.
    @Test
    void delete_oneOfTwoItemsSharingTheSameBinary_leavesTheOthersReferenceIntact() {
        UUID sharedBinary = storeBinary();
        UUID item1 = createBookWithAttachment(sharedBinary);
        UUID item2 = createBookWithAttachment(sharedBinary);
        int afterBothCreated = referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0);
        assertThat(afterBothCreated).isGreaterThanOrEqualTo(2);

        deleteItem(item1);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH)).contains(afterBothCreated - 1);

        deleteItem(item2);

        assertThat(referenceCount(fixture.attachmentPropertyId(), PATH).orElse(0)).isEqualTo(afterBothCreated - 2);
    }
}
