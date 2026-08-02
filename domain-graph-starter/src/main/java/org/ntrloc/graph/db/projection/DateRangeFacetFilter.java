package org.ntrloc.graph.db.projection;

import java.time.LocalDate;

public record DateRangeFacetFilter(String field, LocalDate from, LocalDate to) implements FacetFilter {}
