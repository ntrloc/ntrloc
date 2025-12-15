package org.ntrloc.graph.db.language;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SelectableItemProjectionParsingTest {

    @Test
    void testCreateProjection() throws JsonProcessingException {
        var json = """
                {
                    "itemSelector": {
                        "type": "ITEM_TYPE",
                        "itemType": "Photo"
                    }
                }
                """;
        var objectMapper = new ObjectMapper();
        var projection = objectMapper.readValue(json, SelectableItemProjectionSpec.class);
        assertNotNull(projection);
        assertNotNull(projection.getItemSelector());
    }

}
