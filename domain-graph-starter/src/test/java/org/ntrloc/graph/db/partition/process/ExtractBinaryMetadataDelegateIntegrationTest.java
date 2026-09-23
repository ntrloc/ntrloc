package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Drives the delegate directly (a mocked DelegateExecution, not a full process run) so these two
// no-op paths are deterministic rather than depending on Flowable's async job executor picking up
// the service task on its own schedule. The real gRPC-calling path isn't exercised here -- see
// docs/ntrloc-dynamic-properties-design-notes.md section 9 on why that's verified manually against
// a real media processor for this first pass, not in this automated suite.
class ExtractBinaryMetadataDelegateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ExtractBinaryMetadataDelegate delegate;

    @Autowired
    private JdbcClient jdbcClient;

    // No media-processor.url is configured anywhere in this test suite (see test/resources/
    // application.yml) -- this is that exact "not configured" case, not a special test-only setup.
    @Test
    void execute_whenNoMediaProcessorIsConfigured_doesNothingAndDoesNotThrow() {
        UUID binaryContentId = insertBinaryContentRow("image/jpeg");
        DelegateExecution execution = executionFor(binaryContentId);

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        assertThat(metadataFor(binaryContentId)).isNull();
    }

    @Test
    void execute_forANonImageMimeType_doesNothingAndDoesNotThrow() {
        UUID binaryContentId = insertBinaryContentRow("application/pdf");
        DelegateExecution execution = executionFor(binaryContentId);

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        assertThat(metadataFor(binaryContentId)).isNull();
    }

    private DelegateExecution executionFor(UUID binaryContentId) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(BinaryMetadataExtractionTrigger.BINARY_CONTENT_ID_VARIABLE))
                .thenReturn(binaryContentId.toString());
        return execution;
    }

    private UUID insertBinaryContentRow(String mimeType) {
        return jdbcClient.sql("""
                INSERT INTO binary_content (sha256, md5, mime_type, length, metadata)
                VALUES (:sha256, :md5, :mimeType, 10, '{}'::jsonb)
                RETURNING id
                """)
                .param("sha256", "delegate-test-" + UUID.randomUUID())
                .param("md5", "delegate-test-" + UUID.randomUUID())
                .param("mimeType", mimeType)
                .query(UUID.class)
                .single();
    }

    private String metadataFor(UUID binaryContentId) {
        return jdbcClient.sql("SELECT metadata->>'metadata' FROM binary_content WHERE id = :id")
                .param("id", binaryContentId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
