package org.ntrloc.graph.db.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ItemCreateMutation.class, name = "CREATE"),
        @JsonSubTypes.Type(value = ItemUpdateMutation.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = ItemDeleteMutation.class, name = "DELETE")
})
public sealed interface ItemMutation permits ItemCreateMutation, ItemUpdateMutation, ItemDeleteMutation {
}
