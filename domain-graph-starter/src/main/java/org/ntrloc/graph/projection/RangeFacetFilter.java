package org.ntrloc.graph.projection;

import java.math.BigDecimal;

public record RangeFacetFilter(String field, BigDecimal from, BigDecimal to) implements FacetFilter {}
