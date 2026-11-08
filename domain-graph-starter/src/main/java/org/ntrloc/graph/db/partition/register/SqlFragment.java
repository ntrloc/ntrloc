package org.ntrloc.graph.db.partition.register;

import java.util.Map;

// A piece of SQL text and the named parameters it binds.
record SqlFragment(String sql, Map<String, Object> params) {

    private static final SqlFragment EMPTY = new SqlFragment("", Map.of());

    static SqlFragment empty() {
        return EMPTY;
    }
}
