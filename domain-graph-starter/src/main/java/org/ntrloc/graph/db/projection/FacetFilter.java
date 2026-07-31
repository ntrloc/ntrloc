package org.ntrloc.graph.db.projection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TermsFacetFilter.class,     name = "TERMS"),
    @JsonSubTypes.Type(value = RangeFacetFilter.class,     name = "RANGE"),
    @JsonSubTypes.Type(value = DateRangeFacetFilter.class, name = "DATE_RANGE")
})
public sealed interface FacetFilter permits TermsFacetFilter, RangeFacetFilter, DateRangeFacetFilter {
    String field();
}
