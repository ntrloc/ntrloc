package org.ntrloc.graph.db.partition.schema.repository;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.binary.BinaryDynamicPropertyRegistrar;
import org.ntrloc.graph.db.partition.register.RegisterProjectionTestDomainInitializer;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminBinaryPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.DynamicFieldView;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the admin schema actually surfaces the binary dynamic-property registry (design-notes
// section 10) as AdminBinaryPropertyDefinitionView.dynamicFields, not just that the registrar writes
// the right rows (BinaryDynamicPropertyRegistrarIntegrationTest already covers that in isolation).
class SchemaRepositoryBinaryDynamicFieldsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private BinaryDynamicPropertyRegistrar registrar;

    @Autowired
    private RegisterProjectionTestDomainInitializer fixture;

    @Test
    void attachmentProperty_afterRegisteringADynamicPath_isABinaryVariantExposingItInDynamicFields() {
        UUID propertyId = fixture.attachmentPropertyId();
        String path = "metadata." + UUID.randomUUID();
        registrar.onAttach(propertyId, Map.of("metadata", Map.of(path.substring("metadata.".length()), "sRGB")));
        schemaManager.refreshCache();

        var book = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(fixture.bookTypeId()))
                .findFirst()
                .orElseThrow();
        var attachment = book.properties().stream()
                .filter(p -> p.id().equals(propertyId))
                .findFirst()
                .orElseThrow();

        assertThat(attachment).isInstanceOf(AdminBinaryPropertyDefinitionView.class);
        assertThat(attachment.type()).isEqualTo(PropertyType.BINARY);
        var binaryView = (AdminBinaryPropertyDefinitionView) attachment;
        assertThat(binaryView.dynamicFields()).contains(new DynamicFieldView(path, PropertyType.STRING));

        // The property-selection field list surfaces the same discovery as a flat, immediately
        // requestable entry -- "attachment.metadata.<key>" -- not just the bare "attachment" name.
        // field.path() (here, "path") is already the field's full path within the binary's own
        // fields document, so no extra ".metadata." splice is needed to build the projectable entry.
        assertThat(book.projectableFields()).contains("attachment." + path);
    }
}
