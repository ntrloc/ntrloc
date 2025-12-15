package org.ntrloc.graph.db.language.projection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"  // This field will indicate which subclass
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AllLinksProjectionSpec.class, name = "ALL"),
        @JsonSubTypes.Type(value = SpecificLinksProjectionSpec.class, name = "SPECIFIC")
})
public interface LinksProjectionSpec {
}
