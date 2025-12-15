package org.ntrloc.graph.db.language.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"  // This field will indicate which subclass
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = LinkCreateMutation.class, name = "CREATE"),
        @JsonSubTypes.Type(value = LinkUpdateMutation.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = LinkDeleteMutation.class, name = "DELETE")
})
public abstract class LinkMutation {

}


