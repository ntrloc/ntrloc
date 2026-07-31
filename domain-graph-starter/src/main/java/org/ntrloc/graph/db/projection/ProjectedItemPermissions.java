package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.List;

public record ProjectedItemPermissions(@Nullable List<String> edit, boolean delete) {}
