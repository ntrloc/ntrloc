package org.nterloc.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class GraphQlSchemaParsingTest {

    @Test
    public void testParseSchema() throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("schema1.json");
        ObjectMapper mapper = new ObjectMapper();
        GraphQlSchemaQueryResponse response = mapper.readValue(inputStream, GraphQlSchemaQueryResponse.class);
        assertNotNull(response);
    }

}
