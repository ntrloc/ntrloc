package org.ntrloc.graph.db.language;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"  // This field will indicate which subclass
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BinaryReferenceProperty.class, name = "BINARY"),
        @JsonSubTypes.Type(value = BooleanProperty.class, name = "BOOLEAN"),
        @JsonSubTypes.Type(value = DateProperty.class, name = "DATE"),
        @JsonSubTypes.Type(value = DoubleProperty.class, name = "DOUBLE"),
        @JsonSubTypes.Type(value = IntProperty.class, name = "INTEGER"),
        @JsonSubTypes.Type(value = StringProperty.class, name = "STRING")
})
public interface Property<T> {

    String getName();

    Property<T> renamedTo(String name);

}
