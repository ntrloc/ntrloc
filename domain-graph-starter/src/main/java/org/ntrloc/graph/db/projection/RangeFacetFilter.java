package org.ntrloc.graph.db.projection;

import java.math.BigDecimal;

public record RangeFacetFilter(String field, BigDecimal from, BigDecimal to) implements FacetFilter {}
