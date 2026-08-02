package org.ntrloc.graph.db.projection;

import java.util.List;

public record TermsFacetFilter(String field, List<String> values, boolean includeNull) implements FacetFilter {}
