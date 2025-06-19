package org.ntrloc.graph.storage.impl;

import com.google.common.base.Splitter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;
import org.ntrloc.graph.db.impl.MultipartParser;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class MultipartParserTest {

    private static final Logger LOG = LogManager.getLogger(MultipartParserTest.class);

    private Flux<DataBuffer> stringToBufferFlux(String string, int fluxSize) {
        Iterable<String> iter = Splitter.fixedLength(fluxSize).split(string);
        return Flux.fromIterable(iter).map(s -> {
            DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            LOG.info("Created data buffer: {}", s);
            DataBuffer dataBuffer = dataBufferFactory.allocateBuffer(bytes.length);
            dataBuffer.write(bytes);
            return dataBuffer;
        });
    }

    @Test
    @DisplayName("test parse simple multipart upload")
    void testSimpleMultipartUpload() throws IOException {

        var entityManager = mock(EntityManager.class);

        var writer1 = mock(HashingBinaryDataWriter.class);
        var writer2 = mock(HashingBinaryDataWriter.class);

        doReturn(writer1, writer2).when(entityManager).openWriter();
        doReturn("id1").when(entityManager).commitBinary(writer1);
        doReturn("id2").when(entityManager).commitBinary(writer2);

        var upload = """
                    ----myboundary--
                    Content-Disposition: form-data; name="file1"; filename="my_file_1.txt"
                    Content-Type: text/plain
                    
                    value1
                    ----myboundary--
                    Content-Disposition: form-data; name="file2"; filename="my_file_2.txt"
                    Content-Type: application/octet-stream
                    
                    <file data>
                    ----myboundary----
                    """
                .replaceAll("\n", "\r\n");

        var parser = new MultipartParser("--myboundary--", entityManager);
        Mono<Map<String, String>> result = parser.parse(stringToBufferFlux(upload, 10));
        StepVerifier.create(result).expectNext(Map.of("file1", "id1", "file2", "id2")).verifyComplete();
    }

}
