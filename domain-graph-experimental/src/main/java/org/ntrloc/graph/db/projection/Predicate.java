package org.ntrloc.graph.db.projection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AndPredicate.class,                name = "AND"),
    @JsonSubTypes.Type(value = OrPredicate.class,                 name = "OR"),
    @JsonSubTypes.Type(value = PropertyExistencePredicate.class,  name = "PROPERTY_EXISTS"),
    @JsonSubTypes.Type(value = PropertyValuePredicate.class,      name = "PROPERTY_VALUE")
})
public sealed interface Predicate permits AndPredicate, OrPredicate, PropertyExistencePredicate, PropertyValuePredicate {}
