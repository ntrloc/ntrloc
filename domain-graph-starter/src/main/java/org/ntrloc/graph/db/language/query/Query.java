package org.ntrloc.graph.db.language.query;

public class Query {

    private QuerySelection querySelection;
    private QueryReturn queryReturn;

    public Query(QuerySelection querySelection, QueryReturn queryReturn) {
        this.querySelection = querySelection;
        this.queryReturn = queryReturn;
    }

    public QuerySelection getQuerySelection() {
        return querySelection;
    }

    public QueryReturn getQueryReturn() {
        return queryReturn;
    }

}
