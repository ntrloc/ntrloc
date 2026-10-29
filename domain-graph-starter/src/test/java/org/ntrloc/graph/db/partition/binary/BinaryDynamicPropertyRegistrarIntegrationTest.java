package org.ntrloc.graph.db.partition.binary;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.register.RegisterProjectionTestDomainInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Exercises BinaryDynamicPropertyRegistrar directly (not through a real item mutation -- that's
// RegisterPartitionManager's own attach/detach wiring, covered separately) against
// RegisterProjectionTestDomainInitializer's real "attachment" BINARY property, since
// binary_property_dynamic_property.property_id is a real FK into schema_property.
//
// Every path used here is suffixed with a fresh random marker: the catalog/join tables are global,
// with no per-test scoping mechanism the way item rows have testMarker, so two tests sharing a path
// name would silently see each other's leftover reference counts.
class BinaryDynamicPropertyRegistrarIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BinaryDynamicPropertyRegistrar registrar;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    private String uniquePath(String name) {
        return name + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void onAttach_newPath_mintsACatalogRowAndAOneReferenceJoinRow() {
        UUID propertyId = fixture.attachmentPropertyId();
        String path = uniquePath("colorspace");
        Map<String, Object> metadata = Map.of("extracted", Map.of(path, "sRGB"));

        registrar.onAttach(propertyId, metadata);

        assertThat(referenceCount(propertyId, "extracted." + path)).contains(1);
        assertThat(catalogType("extracted." + path)).contains("STRING");
    }

    @Test
    void onAttach_sameBinaryPathTwice_incrementsTheExistingCount() {
        UUID propertyId = fixture.attachmentPropertyId();
        String path = uniquePath("colorspace");
        Map<String, Object> metadata = Map.of("extracted", Map.of(path, "sRGB"));

        registrar.onAttach(propertyId, metadata);
        registrar.onAttach(propertyId, metadata);

        assertThat(referenceCount(propertyId, "extracted." + path)).contains(2);
    }

    @Test
    void onDetach_theOnlyReference_deletesTheJoinRow() {
        UUID propertyId = fixture.attachmentPropertyId();
        String path = uniquePath("colorspace");
        Map<String, Object> metadata = Map.of("extracted", Map.of(path, "sRGB"));
        registrar.onAttach(propertyId, metadata);

        registrar.onDetach(propertyId, metadata);

        assertThat(referenceCount(propertyId, "extracted." + path)).isEmpty();
    }

    // Mirrors the design-notes section 10 worked example: two items sharing one binary under the
    // same property, one detaching (repointed, cleared, or the item deleted) must not zero out a
    // path the other reference still justifies.
    @Test
    void onDetach_whenAnotherReferenceStillExhibitsThePath_leavesTheJoinRowAlone() {
        UUID propertyId = fixture.attachmentPropertyId();
        String path = uniquePath("colorspace");
        Map<String, Object> metadata = Map.of("extracted", Map.of(path, "sRGB"));
        registrar.onAttach(propertyId, metadata); // item 1
        registrar.onAttach(propertyId, metadata); // item 2, same binary

        registrar.onDetach(propertyId, metadata); // item 1 leaves

        assertThat(referenceCount(propertyId, "extracted." + path)).contains(1);
    }

    @Test
    void onAttach_nestedNumericAndBooleanLeaves_inferTypesCorrectly() {
        UUID propertyId = fixture.attachmentPropertyId();
        String lengthPath = uniquePath("length");
        String gammaPath = uniquePath("gamma");
        String boolPath = uniquePath("interlaced");
        Map<String, Object> metadata = Map.of(lengthPath, 42, gammaPath, 0.454545, boolPath, true);

        registrar.onAttach(propertyId, metadata);

        assertThat(catalogType(lengthPath)).contains("INTEGER");
        assertThat(catalogType(gammaPath)).contains("DECIMAL");
        assertThat(catalogType(boolPath)).contains("BOOLEAN");
    }

    @Test
    void onAttach_arraysAndNulls_areSkippedEntirely() {
        UUID propertyId = fixture.attachmentPropertyId();
        String tagsPath = uniquePath("tags");
        String captionPath = uniquePath("caption");
        String mimeTypePath = uniquePath("mimeType");
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put(tagsPath, Arrays.asList("a", "b"));
        metadata.put(captionPath, null);
        metadata.put(mimeTypePath, "image/jpeg");

        registrar.onAttach(propertyId, metadata);

        assertThat(catalogType(tagsPath)).isEmpty();
        assertThat(catalogType(captionPath)).isEmpty();
        assertThat(catalogType(mimeTypePath)).contains("STRING");
    }

    @Test
    void onAttach_nullOrEmptyMetadata_isANoOp() {
        UUID propertyId = fixture.attachmentPropertyId();

        // No exception is the actual assertion here -- proving both call sites tolerate "no binary
        // set" / "binary not yet extracted" without needing a null-check at the call site.
        registrar.onAttach(propertyId, null);
        registrar.onAttach(propertyId, Map.of());
    }

    private java.util.Optional<Integer> referenceCount(UUID propertyId, String path) {
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

    private java.util.Optional<String> catalogType(String path) {
        return jdbcClient.sql("SELECT type FROM binary_dynamic_property WHERE path = :path")
                .param("path", path)
                .query(String.class)
                .optional();
    }
}
