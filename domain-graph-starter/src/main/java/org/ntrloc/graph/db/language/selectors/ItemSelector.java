package org.ntrloc.graph.db.language.selectors;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"  // This field will indicate which subclass
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ItemTypeSelector.class, name = "ITEM_TYPE"),
        @JsonSubTypes.Type(value = IdSelector.class, name = "ID")
})
public interface ItemSelector extends Selector {
}
