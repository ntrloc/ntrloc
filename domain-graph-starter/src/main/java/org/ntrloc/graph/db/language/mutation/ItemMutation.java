package org.ntrloc.graph.db.language.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"  // This field will indicate which subclass
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ItemCreateMutation.class, name = "CREATE"),
        @JsonSubTypes.Type(value = ItemUpdateMutation.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = ItemDeleteMutation.class, name = "DELETE")
})
public abstract class ItemMutation {

}

