package org.ntrloc.graph.db.partition;

/**
 * A SQL expression written by one store against its own tables, for another store to embed in a query
 * of its own (docs/ntrloc-architecture-rationalization.md §2, rule 6). The owning store writes the
 * template with {@link #KEY} standing for the key it's correlated on; the embedding store supplies a SQL
 * expression for that key, over its own tables. Neither store sees the other's tables.
 */
public record CorrelatedSql(String template) {

    public static final String KEY = "{key}";

    public CorrelatedSql {
        if (!template.contains(KEY)) throw new IllegalArgumentException("Template must contain " + KEY + ": " + template);
    }

    public String bind(String keySql) {
        return template.replace(KEY, keySql);
    }
}
