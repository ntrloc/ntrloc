package org.ntrloc.graph.db.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Standalone, symmetric peer of ItemMutation (Section 6) -- never nested under either
// connected item's own mutation.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LinkCreateMutation.class, name = "CREATE"),
        @JsonSubTypes.Type(value = LinkUpdateMutation.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = LinkDeleteMutation.class, name = "DELETE")
})
public sealed interface LinkMutation permits LinkCreateMutation, LinkUpdateMutation, LinkDeleteMutation {
}
