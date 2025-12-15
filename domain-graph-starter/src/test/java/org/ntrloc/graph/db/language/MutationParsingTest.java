package org.ntrloc.graph.db.language;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.language.mutation.MutationRequest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MutationParsingTest {

    @Test
    void testParseItemCreateMutation() throws JsonProcessingException {
        var json = """
                {
                    "itemMutations": [
                        {
                            "type": "CREATE",
                            "itemType": "Photo",
                            "properties": [
                                {
                                    "type": "STRING",
                                    "name": "name",
                                    "value": "mcpPhoto1"
                                }
                            ]
                        }
                    ]
                }
                """;
        var objectMapper = new ObjectMapper();
        var request = objectMapper.readValue(json, MutationRequest.class);
        assertNotNull(request);
    }

}
